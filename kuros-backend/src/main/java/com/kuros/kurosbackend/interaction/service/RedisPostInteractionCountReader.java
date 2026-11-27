package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.interaction.event.InteractionKind;
import com.kuros.kurosbackend.interaction.redis.InteractionRedisStore;
import com.kuros.kurosbackend.post.service.PostInteractionCountReader;
import com.kuros.kurosbackend.post.service.PostInteractionCounts;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

@Component
public class RedisPostInteractionCountReader implements PostInteractionCountReader {

    private final InteractionRedisStore redisStore;
    private final com.kuros.kurosbackend.post.repository.CommunityPostRepository postRepository;

    public RedisPostInteractionCountReader(InteractionRedisStore redisStore, com.kuros.kurosbackend.post.repository.CommunityPostRepository postRepository) {
        this.redisStore = redisStore;
        this.postRepository = postRepository;
    }

    @Override
    public PostInteractionCounts readForPost(String postId, LongSupplier likeBaseline, LongSupplier favoriteBaseline) {
        long likeCount = redisStore.readCount(InteractionKind.LIKE, postId, () -> postRepository.readCommittedLikeCount(postId));
        long favoriteCount = redisStore.readCount(InteractionKind.FAVORITE, postId, favoriteBaseline);
        return new PostInteractionCounts(likeCount, favoriteCount);
    }
}
