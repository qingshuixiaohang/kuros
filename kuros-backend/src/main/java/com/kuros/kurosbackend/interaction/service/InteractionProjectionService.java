package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.interaction.domain.PostFavorite;
import com.kuros.kurosbackend.interaction.domain.PostFavoriteId;
import com.kuros.kurosbackend.interaction.domain.PostLike;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 互动投影服务（切片 #11）：把一条互动事件「幂等地」落到 MySQL——写关系行 + 刷 DB 冗余计数列。
 *
 * 它是「异步落库」与「同步降级落库」共用的唯一落库逻辑：
 * - 异步：RocketMQ 顺序消费到事件 → InteractionStreamConfig 的 Consumer 调 apply；
 * - 降级：写路径 publish 返回 false（未启用/投递失败）→ 直接调 apply 同步落库。
 * 两处复用同一段代码，保证「最终 DB 状态」一致，避免异步/同步两套逻辑漂移。
 *
 * 幂等如何保证（消费端可能重复投递）？
 * 靠 DB 复合主键 + existsById 前置判断：LIKE 仅在关系不存在时 insert，UNLIKE 仅在存在时 delete。
 * 重复消费同一事件不会二次增减计数——这是「至少一次投递 + 幂等消费」组合的关键。
 *
 * 事务纪律（对应 HANDOFF 陷阱 #7）：
 * apply 用 TransactionTemplate 自开自提交，而非类级 @Transactional。这样在同步降级路径中，
 * 落库事务在「锁内」就已 commit 完成，随后才 unlock——杜绝「先 unlock 后 commit」的竞态窗口。
 *
 * 为什么 apply 上挂 @CacheEvict(postDetail)？
 * DB 计数列变更会使帖子详情缓存（含 likeCount/favoriteCount）过期，必须在真正写 DB 的这一刻驱逐；
 * 挂在投影服务（而非写路径入口）能保证异步消费落库后同样驱逐，缓存与 DB 不脱节。
 */
@Service
public class InteractionProjectionService {

    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final CommunityPostRepository postRepository;
    private final TransactionTemplate transactionTemplate;

    public InteractionProjectionService(PostLikeRepository likeRepository,
                                        PostFavoriteRepository favoriteRepository,
                                        CommunityPostRepository postRepository,
                                        TransactionTemplate transactionTemplate) {
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.postRepository = postRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @CacheEvict(cacheNames = "postDetail", key = "#event.postId()")
    public void apply(InteractionEvent event) {
        transactionTemplate.executeWithoutResult(status -> persist(event));
    }

    private void persist(InteractionEvent event) {
        String postId = event.postId();
        String userId = event.userId();
        LocalDateTime now = LocalDateTime.now();
        switch (event.type()) {
            case LIKE -> {
                PostLikeId id = new PostLikeId(userId, postId);
                if (!likeRepository.existsById(id)) {
                    likeRepository.save(new PostLike(userId, postId, now));
                    postRepository.incrementLikeCount(postId);
                }
            }
            case UNLIKE -> {
                PostLikeId id = new PostLikeId(userId, postId);
                if (likeRepository.existsById(id)) {
                    likeRepository.deleteById(id);
                    postRepository.decrementLikeCount(postId);
                }
            }
            case FAVORITE -> {
                PostFavoriteId id = new PostFavoriteId(userId, postId);
                if (!favoriteRepository.existsById(id)) {
                    favoriteRepository.save(new PostFavorite(userId, postId, now));
                    postRepository.incrementFavoriteCount(postId);
                }
            }
            case UNFAVORITE -> {
                PostFavoriteId id = new PostFavoriteId(userId, postId);
                if (favoriteRepository.existsById(id)) {
                    favoriteRepository.deleteById(id);
                    postRepository.decrementFavoriteCount(postId);
                }
            }
        }
    }
}
