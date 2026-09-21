# Ticket: RateLimitIntegrationTest 集成测试

**依赖**：ticket-03
**阻塞**：ticket-05

## 范围

1. 新建 `ratelimit/RateLimitIntegrationTest.java`
2. `@TestPropertySource` 开启 Sentinel（`app.sentinel.enabled=true`）
3. 测试正常请求返回 200
4. 测试超 QPS 限制时返回 429

## 验收

- 新增测试通过
- 全量测试通过（sentinel.enabled=false 时不影响现有测试）
