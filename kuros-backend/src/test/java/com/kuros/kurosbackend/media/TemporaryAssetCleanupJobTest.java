package com.kuros.kurosbackend.media;

import com.kuros.kurosbackend.media.storage.MediaAssetService;
import com.kuros.kurosbackend.shared.lock.DistributedLock;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TemporaryAssetCleanupJobTest {

    private static final String LOCK_KEY = "lock:temporary-media-cleanup";

    @Test
    void cleansAssetsOlderThanTheConfiguredRetention() {
        MediaAssetService mediaAssetService = mock(MediaAssetService.class);
        TemporaryAssetCleanupJob job = new TemporaryAssetCleanupJob(
                mediaAssetService, lockThatRunsAction(), 60, 120);
        AtomicReference<LocalDateTime> cutoff = new AtomicReference<>();
        LocalDateTime before = LocalDateTime.now().minusMinutes(60);

        job.cleanupExpiredTemporaryAssets();

        verify(mediaAssetService).cleanupTemporaryAssets(argThat(value -> {
            cutoff.set(value);
            return !value.isBefore(before);
        }));
        assertThat(cutoff.get()).isNotNull();
    }

    @Test
    void skipsWhenAnotherInstanceOwnsTheLock() {
        MediaAssetService mediaAssetService = mock(MediaAssetService.class);
        TemporaryAssetCleanupJob job = new TemporaryAssetCleanupJob(
                mediaAssetService, lockHeldByAnotherInstance(), 60, 120);

        job.cleanupExpiredTemporaryAssets();

        verifyNoInteractions(mediaAssetService);
    }

    private static DistributedLock lockThatRunsAction() {
        return new DistributedLock(null) {
            @Override
            public <T> T withLock(String key, int ttlSeconds, java.util.function.Supplier<T> action) {
                assertThat(key).isEqualTo(LOCK_KEY);
                assertThat(ttlSeconds).isEqualTo(120);
                return action.get();
            }
        };
    }

    private static DistributedLock lockHeldByAnotherInstance() {
        return new DistributedLock(null) {
            @Override
            public <T> T withLock(String key, int ttlSeconds, java.util.function.Supplier<T> action) {
                assertThat(key).isEqualTo(LOCK_KEY);
                assertThat(ttlSeconds).isEqualTo(120);
                return null;
            }
        };
    }
}
