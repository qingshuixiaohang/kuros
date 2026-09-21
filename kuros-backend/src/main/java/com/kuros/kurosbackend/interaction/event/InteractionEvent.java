package com.kuros.kurosbackend.interaction.event;

import java.util.UUID;

/**
 * 互动事件（切片 #11）：写路径发到 RocketMQ、消费端据此幂等落库的载体。
 *
 * - eventId：UUID，用于日志追踪与死信排查（幂等本身靠 DB 复合主键 + existsById，不依赖它去重）
 * - postId：既是业务主键，也是顺序消息的分区键（同一帖的互动串行到单队列）
 * - occurredAt：epoch milli，记录操作发生时刻（消费端落库的 createdAt 可用它，也可用消费时刻）
 *
 * 用 record：不可变、天然携带 JSON 序列化所需的组件访问器（Spring Cloud Stream 默认 application/json）。
 */
public record InteractionEvent(
        String eventId,
        String postId,
        String userId,
        InteractionType type,
        long occurredAt
) {

    public static InteractionEvent of(String postId, String userId, InteractionType type) {
        return new InteractionEvent(UUID.randomUUID().toString(), postId, userId, type, System.currentTimeMillis());
    }
}
