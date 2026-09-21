package com.kuros.kurosuser.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应包装（split-06 自 kuros-backend 迁入）。
 * 认证域的 API 契约与 backend 完全一致：data 包装 + meta 缺省省略，
 * 前端对 auth 路径的解析逻辑零改动。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, PageMeta meta) {
}
