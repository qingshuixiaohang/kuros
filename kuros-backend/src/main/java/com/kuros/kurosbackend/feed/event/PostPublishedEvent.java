package com.kuros.kurosbackend.feed.event;

import java.time.LocalDateTime;

/**
 * 帖子发布成功事件（Spring Application Event）。
 *
 * 由 PostPublishingService.publish() 在事务内 emit，由 FeedFanoutListener
 * 在事务提交后（@TransactionalEventListener AFTER_COMMIT）处理。
 *
 * 为什么用 ApplicationEvent 而不是直接调用 FeedTimelineStore？
 * ① 解耦：发帖服务不感知 feed 模块的存在（单向依赖 post → feed）。
 * ② 时序保证：AFTER_COMMIT 确保帖子已持久化，粉丝读 timeline 时能查到帖子详情。
 * ③ 异常隔离：扇出失败（Feign 超时/Redis 抖动）不影响发帖成功响应。
 */
public record PostPublishedEvent(
        String postId,
        String authorId,
        LocalDateTime publishedAt
) {
}
