package com.kuros.kurosbackend.interaction;

import com.kuros.kurosbackend.interaction.service.InteractionCountReconciler;
import com.kuros.kurosbackend.interaction.service.InteractionCountReconciliationJob;
import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.shared.lock.DistributedLock;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionCountReconciliationJobTest {

    private static final String LOCK_KEY = "lock:interaction-count-reconciliation";

    @Test
    void scansPublishedPostIdsInBoundedBatches() {
        List<String> scanned = new ArrayList<>();
        CommunityPostRepository postRepository = repository(Map.of(
                "", List.of("post-1", "post-2"),
                "post-2", List.of("post-3")));

        job(scanned, null, postRepository, lockThatRunsAction()).reconcilePublishedPosts();

        assertThat(scanned).containsExactly("post-1", "post-2", "post-3");
        assertThat(scanned).doesNotHaveDuplicates();
    }

    @Test
    void continuesAfterOnePostFails() {
        List<String> scanned = new ArrayList<>();
        CommunityPostRepository postRepository = repository(Map.of(
                "", List.of("post-1", "post-2"),
                "post-2", List.of()));

        job(scanned, "post-1", postRepository, lockThatRunsAction()).reconcilePublishedPosts();

        assertThat(scanned).containsExactly("post-1", "post-2");
    }

    @Test
    void skipsWhenAnotherInstanceOwnsTheLock() {
        List<String> scanned = new ArrayList<>();
        CommunityPostRepository postRepository = repository(Map.of("", List.of("post-1")));

        job(scanned, null, postRepository, lockHeldByAnotherInstance()).reconcilePublishedPosts();

        assertThat(scanned).isEmpty();
    }

    @Test
    void stopsBeforeProcessingAnotherPostAfterLosingTheLock() {
        List<String> scanned = new ArrayList<>();
        CommunityPostRepository postRepository = repository(Map.of(
                "", List.of("post-1", "post-2"),
                "post-2", List.of()));
        AtomicInteger ownershipChecks = new AtomicInteger();
        DistributedLock lockLostDuringScan = new DistributedLock(null) {
            @Override
            public <T> T withLockWhileOwned(String key, int ttlSeconds, Function<BooleanSupplier, T> action) {
                return action.apply(() -> ownershipChecks.incrementAndGet() <= 2);
            }
        };

        job(scanned, null, postRepository, lockLostDuringScan).reconcilePublishedPosts();

        assertThat(scanned).containsExactly("post-1");
    }

    private static InteractionCountReconciliationJob job(
            List<String> scanned,
            String failingPostId,
            CommunityPostRepository postRepository,
            DistributedLock distributedLock) {
        InteractionCountReconciler reconciler = new InteractionCountReconciler(
                null, null, null, null, (TransactionTemplate) null) {
            @Override
            public void reconcilePublishedPostCounts(String postId) {
                scanned.add(postId);
                if (postId.equals(failingPostId)) {
                    throw new IllegalStateException("simulated reconciliation failure");
                }
            }
        };
        return new InteractionCountReconciliationJob(reconciler, postRepository, distributedLock, 2, 30);
    }

    private static CommunityPostRepository repository(Map<String, List<String>> batchesByCursor) {
        Set<String> requestedCursors = new HashSet<>();
        return (CommunityPostRepository) Proxy.newProxyInstance(
                CommunityPostRepository.class.getClassLoader(),
                new Class<?>[]{CommunityPostRepository.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("findPublishedIdsAfter")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    assertThat(args[0]).isEqualTo(PostStatus.PUBLISHED);
                    assertThat(requestedCursors.add((String) args[1])).isTrue();
                    assertThat(args[1]).isIn(batchesByCursor.keySet());
                    assertThat(((Pageable) args[2]).getPageSize()).isEqualTo(2);
                    return batchesByCursor.get(args[1]);
                });
    }

    private static DistributedLock lockThatRunsAction() {
        return new DistributedLock(null) {
            @Override
            public <T> T withLockWhileOwned(String key, int ttlSeconds, Function<BooleanSupplier, T> action) {
                assertThat(key).isEqualTo(LOCK_KEY);
                assertThat(ttlSeconds).isEqualTo(30);
                return action.apply(() -> true);
            }
        };
    }

    private static DistributedLock lockHeldByAnotherInstance() {
        return new DistributedLock(null) {
            @Override
            public <T> T withLockWhileOwned(String key, int ttlSeconds, Function<BooleanSupplier, T> action) {
                return null;
            }
        };
    }
}
