package com.kuros.kurosbackend.interaction.service;

import com.kuros.kurosbackend.post.domain.PostStatus;
import com.kuros.kurosbackend.post.repository.CommunityPostRepository;
import com.kuros.kurosbackend.shared.lock.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class InteractionCountReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(InteractionCountReconciliationJob.class);
    private static final String LOCK_KEY = "lock:interaction-count-reconciliation";

    private final InteractionCountReconciler reconciler;
    private final CommunityPostRepository postRepository;
    private final DistributedLock distributedLock;
    private final int batchSize;
    private final int lockTtlSeconds;

    public InteractionCountReconciliationJob(
            InteractionCountReconciler reconciler,
            CommunityPostRepository postRepository,
            DistributedLock distributedLock,
            @Value("${app.interaction.reconciliation.batch-size:100}") int batchSize,
            @Value("${app.interaction.reconciliation.lock-ttl-seconds:30}") int lockTtlSeconds) {
        this.reconciler = reconciler;
        this.postRepository = postRepository;
        this.distributedLock = distributedLock;
        this.batchSize = batchSize;
        this.lockTtlSeconds = lockTtlSeconds;
    }

    @Scheduled(
            initialDelayString = "${app.interaction.reconciliation.initial-delay-ms:300000}",
            fixedDelayString = "${app.interaction.reconciliation.fixed-delay-ms:86400000}")
    public void reconcilePublishedPosts() {
        Boolean completed = distributedLock.withLockWhileOwned(LOCK_KEY, lockTtlSeconds, lockOwned -> {
            log.info("开始执行已发布帖子互动计数对账，batchSize={}", batchSize);
            int scanned = 0;
            int failed = 0;
            String afterId = "";
            boolean aborted = false;

            while (true) {
                if (!lockOwned.getAsBoolean()) {
                    aborted = true;
                    break;
                }
                List<String> postIds = postRepository.findPublishedIdsAfter(
                        PostStatus.PUBLISHED, afterId, PageRequest.of(0, batchSize));
                if (postIds.isEmpty()) {
                    break;
                }

                for (String postId : postIds) {
                    if (!lockOwned.getAsBoolean()) {
                        aborted = true;
                        break;
                    }
                    scanned++;
                    try {
                        reconciler.reconcilePublishedPostCounts(postId);
                    } catch (RuntimeException exception) {
                        failed++;
                        log.error("帖子互动计数对账失败：postId={}", postId, exception);
                    }
                }

                if (aborted) {
                    break;
                }
                afterId = postIds.get(postIds.size() - 1);
                if (postIds.size() < batchSize) {
                    break;
                }
            }

            log.info("已发布帖子互动计数对账{}：scanned={} failed={}", aborted ? "因锁丢失提前停止" : "完成", scanned, failed);
            return !aborted;
        });

        if (completed == null) {
            log.info("跳过已发布帖子互动计数对账：其他实例正在执行");
        }
    }
}
