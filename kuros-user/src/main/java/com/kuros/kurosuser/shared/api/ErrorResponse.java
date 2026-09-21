package com.kuros.kurosuser.shared.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 统一错误响应（split-06 自 kuros-backend 迁入，字段逐字一致）。
 * 前端读 code 做分支、读 message 展示，契约与 backend 错误响应相同——
 * 同一客户端代码在拆分前后收到同样结构的错误体。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, Map<String, String> fieldErrors) {
}
