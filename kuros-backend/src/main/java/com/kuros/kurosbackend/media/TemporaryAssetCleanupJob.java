package com.kuros.kurosbackend.media;

import com.kuros.kurosbackend.media.storage.MediaAssetService;
import com.kuros.kurosbackend.shared.lock.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TemporaryAssetCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TemporaryAssetCleanupJob.class);
    private static final String LOCK_KEY = "lock:temporary-media-cleanup";

    private final MediaAssetService mediaAssetService;
    private final DistributedLock distributedLock;
    private final int retentionMinutes;
    private final int lockTtlSeconds;

    public TemporaryAssetCleanupJob(
            MediaAssetService mediaAssetService,
            DistributedLock distributedLock,
            @Value("${app.media.cleanup.retention-minutes:1440}") int retentionMinutes,
            @Value("${app.media.cleanup.lock-ttl-seconds:120}") int lockTtlSeconds
    ) {
        this.mediaAssetService = mediaAssetService;
        this.distributedLock = distributedLock;
        this.retentionMinutes = retentionMinutes;
        this.lockTtlSeconds = lockTtlSeconds;
    }

    @Scheduled(
            initialDelayString = "${app.media.cleanup.initial-delay-ms:300000}",
            fixedDelayString = "${app.media.cleanup.fixed-delay-ms:3600000}")
    public void cleanupExpiredTemporaryAssets() {
        Integer cleaned = distributedLock.withLock(
                LOCK_KEY,
                lockTtlSeconds,
                () -> mediaAssetService.cleanupTemporaryAssets(
                        LocalDateTime.now().minusMinutes(retentionMinutes)));
        if (cleaned == null) {
            log.info("跳过媒体临时资产清理：其他实例正在执行");
            return;
        }
        log.info("媒体临时资产清理完成：cleaned={}", cleaned);
    }
}
