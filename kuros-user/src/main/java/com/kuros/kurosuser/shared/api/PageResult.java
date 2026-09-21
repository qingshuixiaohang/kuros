package com.kuros.kurosuser.shared.api;

import java.util.List;

/**
 * 分页结果（split-07 自 kuros-backend 迁入，结构逐字一致）。
 *
 * 内部 API 的关注/粉丝列表用它返回：items + meta——
 * split-08 的 backend Feign 消费方按同款结构解析（PageMeta 自 split-06 起已在本服务等待消费）。
 */
public record PageResult<T>(List<T> items, PageMeta meta) {
}
