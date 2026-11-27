package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.interaction.domain.PostFavorite;
import com.kuros.kurosbackend.interaction.domain.PostFavoriteId;
import com.kuros.kurosbackend.interaction.domain.PostLike;
import com.kuros.kurosbackend.interaction.domain.PostLikeId;
import com.kuros.kurosbackend.interaction.event.InteractionEvent;
import com.kuros.kurosbackend.interaction.redis.InteractionRedisStore;
import com.kuros.kurosbackend.interaction.repository.PostFavoriteRepository;
import com.kuros.kurosbackend.interaction.repository.PostLikeRepository;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
public class InteractionProjectionService {

    private final PostLikeRepository likeRepository;
    private final PostFavoriteRepository favoriteRepository;
    private final CommunityPostRepository postRepository;
    private final TransactionTemplate transactionTemplate;
    private final InteractionRedisStore redisStore;

    public InteractionProjectionService(PostLikeRepository likeRepository,
                                        PostFavoriteRepository favoriteRepository,
                                        CommunityPostRepository postRepository,
                                        TransactionTemplate transactionTemplate,
                                        InteractionRedisStore redisStore) {
        this.likeRepository = likeRepository;
        this.favoriteRepository = favoriteRepository;
        this.postRepository = postRepository;
        this.transactionTemplate = transactionTemplate;
        this.redisStore = redisStore;
    }

    public void apply(InteractionEvent event) {
        transactionTemplate.executeWithoutResult(status -> persist(event));
        // 重投也要失效缓存，前一次可能在提交之后、失效之前失败。
        redisStore.invalidate(event.type().kind(), event.postId(), event.userId());
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
