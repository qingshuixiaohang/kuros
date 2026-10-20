package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.interaction.event.InteractionKind;
import com.kuros.kurosbackend.interaction.redis.InteractionRedisStore;
import com.kuros.kurosbackend.post.service.PostInteractionCounts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class RedisPostInteractionCountReaderTest {

    @Test
    void returnsLiveRedisCountsWithoutEvaluatingDatabaseBaselines() {
        TestInteractionRedisStore redisStore = new TestInteractionRedisStore();
        redisStore.liveCounts.put(InteractionKind.LIKE, 12L);
        redisStore.liveCounts.put(InteractionKind.FAVORITE, 3L);
        RedisPostInteractionCountReader reader = new RedisPostInteractionCountReader(redisStore);
        AtomicInteger baselineReads = new AtomicInteger();

        PostInteractionCounts counts = reader.readForPost("post-1", baselineReads::incrementAndGet,
                baselineReads::incrementAndGet);

        assertThat(counts).isEqualTo(new PostInteractionCounts(12L, 3L));
        assertThat(baselineReads).hasValue(0);
        assertThat(redisStore.kinds).containsExactly(InteractionKind.LIKE, InteractionKind.FAVORITE);
        assertThat(redisStore.postIds).containsExactly("post-1", "post-1");
    }

    @Test
    void delegatesEachDatabaseBaselineToItsMatchingRedisCount() {
        TestInteractionRedisStore redisStore = new TestInteractionRedisStore();
        RedisPostInteractionCountReader reader = new RedisPostInteractionCountReader(redisStore);

        PostInteractionCounts counts = reader.readForPost("post-2", () -> 21L, () -> 5L);

        assertThat(counts).isEqualTo(new PostInteractionCounts(21L, 5L));
        assertThat(redisStore.kinds).containsExactly(InteractionKind.LIKE, InteractionKind.FAVORITE);
        assertThat(redisStore.postIds).containsExactly("post-2", "post-2");
    }

    private static final class TestInteractionRedisStore extends InteractionRedisStore {

        private final Map<InteractionKind, Long> liveCounts = new EnumMap<>(InteractionKind.class);
        private final List<InteractionKind> kinds = new ArrayList<>();
        private final List<String> postIds = new ArrayList<>();

        private TestInteractionRedisStore() {
            super(null, 86_400);
        }

        @Override
        public long readCount(InteractionKind kind, String postId, LongSupplier dbBaseline) {
            kinds.add(kind);
            postIds.add(postId);
            return liveCounts.containsKey(kind) ? liveCounts.get(kind) : dbBaseline.getAsLong();
        }
    }
}
