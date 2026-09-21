package com.kuros.kurosuser.api;

/**
 * 关注状态响应（split-07 自 kuros-backend 迁入，字段逐字一致）。
 *
 * 契约冻结的原因：前端 lib/api.ts 的 UserFollow 类型逐字依赖这三个字段——
 * 迁移到本服务后，同一前端代码经网关收到的响应结构零改动。
 */
public record UserFollowResponse(
        String targetUserId,
        long followerCount,
        boolean followed
) {
}
