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

    public RedisPostInteractionCountReader(InteractionRedisStore redisStore) {
        this.redisStore = redisStore;
    }

    @Override
    public PostInteractionCounts readForPost(String postId, LongSupplier likeBaseline, LongSupplier favoriteBaseline) {
        long likeCount = redisStore.readCount(InteractionKind.LIKE, postId, likeBaseline);
        long favoriteCount = redisStore.readCount(InteractionKind.FAVORITE, postId, favoriteBaseline);
        return new PostInteractionCounts(likeCount, favoriteCount);
    }
}
