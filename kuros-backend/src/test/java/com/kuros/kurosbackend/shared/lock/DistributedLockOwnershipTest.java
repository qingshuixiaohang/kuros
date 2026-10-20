package com.kuros.kurosbackend.shared.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockOwnershipTest {

    @Test
    void renew抛出异常时ownershipSupplier报告锁已丢失() {
        DistributedLock lock = new DistributedLock(null) {
            @Override
            public String tryLock(String key, int ttlSeconds) {
                return "lock-token";
            }

            @Override
            public boolean renew(String key, String value, int ttlSeconds) {
                throw new IllegalStateException("simulated Redis failure");
            }

            @Override
            public void unlock(String key, String value) {
            }
        };

        Boolean stillOwned = lock.withLockWhileOwned("lock:test", 1, owned -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (owned.getAsBoolean() && System.nanoTime() < deadline) {
                try {
                    TimeUnit.MILLISECONDS.sleep(20);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
            return owned.getAsBoolean();
        });

        assertThat(stillOwned).isFalse();
    }
}
