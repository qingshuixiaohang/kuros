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
import com.kuros.kurosbackend.shared.exception.ServiceUnavailableException;
import com.kuros.kurosbackend.shared.lock.DistributedLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PostInteractionService {

    private final CommunityPostRepository postRepository;
    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final DistributedLock distributedLock;
    /**
     * 互动锁 TTL。持锁期间由 DistributedLock.withLock 的看门狗自动续期，
     * 所以这个值是"续期周期"而非"硬上限"——业务再慢也不会丢互斥。
     */
    private static final int LOCK_TTL_SECONDS = 3;

    private final InteractionRedisStore redisStore;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionProjectionService projectionService;
    private final InteractionAcceptanceService acceptanceService;

    public PostInteractionService(
            CommunityPostRepository postRepository,
            PostLikeRepository likeRepository,
            PostFavoriteRepository favoriteRepository,
            DistributedLock distributedLock,
            InteractionRedisStore redisStore,
            InteractionEventPublisher eventPublisher,
            InteractionProjectionService projectionService,
            InteractionAcceptanceService acceptanceService
    ) {
        this.postRepository = postRepository;
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.distributedLock = distributedLock;
        this.redisStore = redisStore;
        this.eventPublisher = eventPublisher;
        this.projectionService = projectionService;
        this.acceptanceService = acceptanceService;
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

    /**
     * 批量读互动**关系状态**（前端审计 H-2：首页 Feed N+1 消除）。
     *
     * 为什么刻意不返回计数：列表接口（/api/v1/posts）早已返回
     * likeCount/favoriteCount，卡片上的计数不需要额外请求。N+1 只发生在
     * "当前用户是否已赞/已藏"这个 per-user 状态上——它无法从列表响应推导，
     * 必须单独查。所以这里只查关系，计数让列表响应提供，两边不重复返回
     * 同一个值（重复即诱发"哪个才是真相"的问题）。
     *
     * 未登录直接全 false：游客没有关系状态，查 DB 也是空结果，
     * 但省掉两次 IN 查询（且避免 null userId 传进查询）。
     *
     * @param postIds 帖子 id 列表（一页的量，通常 ≤ 20）
     * @param userId  当前用户 id，未登录传 null
     * @return postId → {liked, favorited}；每个输入的 postId 都有条目
     *         （无关系的帖子是 {false, false}），调用方不必再判空
     */
    @Transactional(readOnly = true)
    public Map<String, boolean[]> findStatesFor(Collection<String> postIds, String userId) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinctIds = postIds.stream().distinct().toList();
        Map<String, boolean[]> states = new HashMap<>();
        for (String postId : distinctIds) {
            states.put(postId, new boolean[]{false, false});
        }
        if (userId == null) {
            return states;
        }
        // 两次常数查询覆盖整页，与帖子数无关（这是消除 N+1 的硬指标）
        for (String postId : likeRepository.findLikedPostIds(userId, distinctIds)) {
            boolean[] state = states.get(postId);
            if (state != null) {
                state[0] = true;
            }
        }
        for (String postId : favoriteRepository.findFavoritedPostIds(userId, distinctIds)) {
            boolean[] state = states.get(postId);
            if (state != null) {
                state[1] = true;
            }
        }
        return states;
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

    public void submit(String postId, String userId, String operationId, InteractionType type) {
        String lockKey = "lock:" + type.kind().keyPrefix() + ":" + postId + ":" + userId;
        Boolean accepted = distributedLock.withLock(lockKey, LOCK_TTL_SECONDS, () -> {
            findPublishedPost(postId);
            InteractionEvent event = new InteractionEvent(operationId, postId, userId, type, System.currentTimeMillis());
            projectionService.requireCompatible(event);
            switch (acceptanceService.submit(event)) {
                case DISABLED -> throw new IllegalStateException("Unexpected delivery state");
                case REJECTED -> throw new ServiceUnavailableException("INTERACTION_NOT_ACCEPTED", "互动暂无法受理，请稍后重试");
                case UNKNOWN -> throw new ServiceUnavailableException("INTERACTION_DELIVERY_UNKNOWN", "互动投递结果暂未确认");
                case SENT -> { }
            }
            return true;
        });
        if (accepted == null) {
            throw new ServiceUnavailableException("INTERACTION_NOT_ACCEPTED", "上一条互动请求正在提交，请稍后重试");
        }
    }

    private PostInteractionResponse interact(String postId, String userId, InteractionType type, String lockPrefix) {
        String lockKey = "lock:" + lockPrefix + ":" + postId + ":" + userId;
        PostInteractionResponse result = distributedLock.withLock(lockKey, LOCK_TTL_SECONDS, () -> {
            CommunityPost post = findPublishedPost(postId);
            InteractionEvent event = InteractionEvent.of(postId, userId, type);
            return switch (eventPublisher.publish(event)) {
                case DISABLED -> {
                    projectionService.apply(event);
                    yield findPost(postId, userId);
                }
                case REJECTED -> throw new ServiceUnavailableException("INTERACTION_NOT_ACCEPTED", "互动暂无法受理，请稍后重试");
                case UNKNOWN -> throw new ServiceUnavailableException("INTERACTION_DELIVERY_UNKNOWN", "互动投递结果暂未确认");
                case SENT -> {
                    // 旧接口仍返回快照。异步写请求不回填缓存，避免和消费者的失效操作竞争。
                    yield new PostInteractionResponse(postId, post.getLikeCount(), post.getFavoriteCount(),
                            likeRepository.existsById(new PostLikeId(userId, postId)),
                            favoriteRepository.existsById(new PostFavoriteId(userId, postId)));
                }
            };
        });
        if (result == null) {
            throw new ServiceUnavailableException("INTERACTION_NOT_ACCEPTED", "上一条互动请求正在提交，请稍后重试");
        }
        return result;
    }

    private CommunityPost findPublishedPost(String postId) {
        return postRepository.findByIdAndStatus(postId, PostStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("POST_NOT_FOUND", "帖子不存在或已删除"));
    }
}
