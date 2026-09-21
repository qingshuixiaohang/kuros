package com.kuros.kurosuser.shared.api;

/**
 * 分页元信息（split-06 自 kuros-backend 迁入）。
 * 认证域本身不用分页，但 ApiResponse 的 meta 组件类型必须存在；
 * split-08 起用户域列表接口（关注列表等）会真正消费它。
 */
public record PageMeta(int page, int pageSize, long totalItems, int totalPages) {
}
