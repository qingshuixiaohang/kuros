# Ticket: SaTokenConfigure 白名单放行 Swagger 路径

**依赖**：ticket-01
**阻塞**：ticket-03

## 范围

1. `SaTokenConfigure` 白名单添加 `/swagger-ui/**` 和 `/v3/api-docs/**`
2. 允许匿名访问 Swagger UI 和 OpenAPI JSON

## 验收

- 未登录状态下 `/swagger-ui/index.html` 返回 200
- `/v3/api-docs` 返回 200
