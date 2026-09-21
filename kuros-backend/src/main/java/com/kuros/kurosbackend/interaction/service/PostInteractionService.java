package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.interaction.api.PostInteractionResponse;
import com.kuros.kurosbackend.interaction.domain.PostFavoriteId;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.event.InteractionEventPublisher;
import com.kuros.kurosbackend.interaction.event.InteractionKind;
import com.kuros.kurosbackend.interaction.event.InteractionType;
import com.kuros.kurosbackend.interaction.redis.InteractionRedisStore;
import com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.post.domain.CommunityPost;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.shared.exception.ResourceNotFoundException;
import com.kuros.kurosbackend.shared.lock.DistributedLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 帖子互动服务（切片 #11 改造）：点赞/收藏写路径由「同步写 MySQL」改为「Redis 计数前置 + RocketMQ 异步落库」。
 *
 * 写路径（like/favorite/unlike/unfavorite）新时序：
 *   per-user 锁 → 校验帖子存在 → Redis 翻转（实时计数即时返回）→ 投递 MQ（失败则同步降级落库）→ 解锁
 * 请求线程只碰 Redis（O(1)），不再等待 DB 写；跨用户对热点帖计数行的 DB 行锁竞争，
 * 被「按 postId 顺序消费」串行化到消费端单队列单线程根除（ADR 0004）。
 *
 * 关键纪律：本类**不加类级 @Transactional**。
 * 落库统一委托 InteractionProjectionService.apply（内部 TransactionTemplate 自开自提交），
 * 使降级同步落库的 commit 发生在锁内、unlock 之前——规避「先解锁后提交」竞态（HANDOFF 陷阱 #7）。
 * 缓存驱逐也随之下沉到 apply（真正改 DB 的那一刻），异步/降级两条路都能正确失效 postDetail。
 *
 * 锁粒度：lock:{like|favorite}:{postId}:{userId} 只串行化「同一用户对同一帖」的并发点击，不跨用户互斥；
 * 跨用户竞争已由消费端顺序消费根除，无需再用粗粒度锁把热帖写路径堵成单线程。
 */
@Service
public class PostInteractionService {

    private final CommunityPostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final DistributedLock distributedLock;
    private final InteractionRedisStore redisStore;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionProjectionService projectionService;

    public PostInteractionService(
            CommunityPostRepository postRepository,
            PostLikeRepository likeRepository,
            PostFavoriteRepository favoriteRepository,
            DistributedLock distributedLock,
            InteractionRedisStore redisStore,
            InteractionEventPublisher eventPublisher,
            InteractionProjectionService projectionService
    ) {
        this.postRepository = postRepository;
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.distributedLock = distributedLock;
        this.redisStore = redisStore;
        this.eventPublisher = eventPublisher;
        this.projectionService = projectionService;
    }

    /** 读互动状态：计数与关系状态均 Redis 优先，miss 回源 DB（冗余计数列 / 关系行）并回填。 */
    @Transactional(readOnly = true)
    public PostInteractionResponse findPost(String postId, String userId) {
        CommunityPost post = findPublishedPost(postId);
        long likeCount = redisStore.readCount(InteractionKind.LIKE, postId, post::getLikeCount);
        long favoriteCount = redisStore.readCount(InteractionKind.FAVORITE, postId, post::getFavoriteCount);
        boolean liked = userId != null && redisStore.readState(InteractionKind.LIKE, postId, userId,
                () -> likeRepository.existsById(new PostLikeId(userId, postId)));
        boolean favorited = userId != null && redisStore.readState(InteractionKind.FAVORITE, postId, userId,
                () -> favoriteRepository.existsById(new PostFavoriteId(userId, postId)));
        return new PostInteractionResponse(postId, likeCount, favoriteCount, liked, favorited);
    }

    public PostInteractionResponse like(String postId, String userId) {
        return interact(postId, userId, InteractionType.LIKE, "like");
    }

    public PostInteractionResponse favorite(String postId, String userId) {
        return interact(postId, userId, InteractionType.FAVORITE, "favorite");
    }

    public PostInteractionResponse unlike(String postId, String userId) {
        return interact(postId, userId, InteractionType.UNLIKE, "like");
    }

    public PostInteractionResponse unfavorite(String postId, String userId) {
        return interact(postId, userId, InteractionType.UNFAVORITE, "favorite");
    }

    /**
     * 互动写路径统一入口：锁 → Redis 翻转 → 投递/降级 → 返回实时快照。
     * 未抢到锁（同用户并发点击）直接返回当前快照，不阻塞、不重复计。
     */
    private PostInteractionResponse interact(String postId, String userId, InteractionType type, String lockPrefix) {
        String lockKey = "lock:" + lockPrefix + ":" + postId + ":" + userId;
        String lockValue = distributedLock.tryLock(lockKey, 3);
        if (lockValue == null) {
            return findPost(postId, userId);
        }
        try {
            CommunityPost post = findPublishedPost(postId);
            // Redis 翻转：仅在状态真正变化时增减计数；key miss 时用传入的 DB 供给器回源回填
            redisStore.flip(type, postId, userId,
                    () -> existsRelationInDb(type, postId, userId),
                    () -> baselineCount(type, post));
            // 投递 MQ；未启用异步或投递失败 → 同步降级落库（apply 在锁内 commit），互动绝不丢
            InteractionEvent event = InteractionEvent.of(postId, userId, type);
            if (!eventPublisher.publish(event)) {
                projectionService.apply(event);
            }
            return findPost(postId, userId);
        } finally {
            distributedLock.unlock(lockKey, lockValue);
        }
    }

    private boolean existsRelationInDb(InteractionType type, String postId, String userId) {
        return switch (type.kind()) {
            case LIKE -> likeRepository.existsById(new PostLikeId(userId, postId));
            case FAVORITE -> favoriteRepository.existsById(new PostFavoriteId(userId, postId));
        };
    }

    private long baselineCount(InteractionType type, CommunityPost post) {
        return type.kind() == InteractionKind.LIKE ? post.getLikeCount() : post.getFavoriteCount();
    }

    private CommunityPost findPublishedPost(String postId) {
        return postRepository.findByIdAndStatus(postId, PostStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("帖子不存在或已删除"));
    }
}
