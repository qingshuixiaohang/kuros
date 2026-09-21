# Ticket: SentinelRateLimitInterceptor 实现

**依赖**：ticket-01
**阻塞**：ticket-03

## 范围

1. 新建 `config/SentinelRateLimitInterceptor.java`：实现 `HandlerInterceptor`
2. `preHandle()` 中调用 `SphU.entry(resource)` + `try-finally` 确保 `entry.exit()`
3. 限流时返回 HTTP 429 + JSON 错误体
4. 资源名从请求路径提取（如 `/api/posts/list` → `api-posts-list`）

## 验收

- 拦截器编译通过
- SphU.entry()/exit() 成对调用，无资源泄漏
