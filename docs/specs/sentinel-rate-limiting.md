# Spec: Sentinel 基础限流保护 API

## 概述

使用 Alibaba Sentinel 为核心 API 添加 QPS 限流，防止恶意刷接口。限流触发时返回 HTTP 429 + JSON。

## 限流规则

| 资源名 | QPS 阈值 | 路径 | 说明 |
|---|---|---|---|
| `api-posts-list` | 100 | GET /api/v1/posts | 帖子列表 |
| `api-post-detail` | 50 | GET /api/v1/posts/{id} | 帖子详情 |
| `api-default` | 200 | /api/** 其余 | 兜底限流 |

> split-06 变更：原 `api-auth-code`（POST /api/v1/auth/code）规则已随认证链路迁至 kuros-user；
> 该服务暂未引入 Sentinel（Q15-A 决策），认证防刷当前依赖 Redis 的 60s/手机号节流。

## 技术方案

- **SDK**：`sentinel-core`（纯 Java，不依赖 Dashboard）
- **集成**：自定义 `HandlerInterceptor` + `SphU.entry()` / `entry.exit()`
- **拦截器顺序**：order=-1（在 SaToken 鉴权之前执行，限流优先）
- **响应格式**：HTTP 429 + `{"code":"RATE_LIMITED","message":"请求过于频繁，请稍后再试"}`
- **规则管理**：`@PostConstruct` 中 `FlowRuleManager.loadRules()` 注册规则
- **开关**：`app.sentinel.enabled=true/false`（test profile 中关闭）

## 不做的事

1. 不引入 Sentinel Dashboard（学习项目不需要可视化）
2. 不使用 `sentinel-spring-webmvc-v6x-adapter`（版本兼容性不确定，自定义 Interceptor 更可控）
3. 不做熔断降级（下一切片或需要 Dashboard 时再加）
