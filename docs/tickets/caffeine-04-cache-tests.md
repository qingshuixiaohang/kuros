# Ticket: 缓存行为测试

**依赖**：ticket-02, ticket-03
**阻塞**：ticket-05

## 范围

新建 `CacheIntegrationTest.java`（使用独立 profile 启用缓存）：
1. 测试 1：连续两次调用 `findPublishedById`，第二次应命中缓存
2. 测试 2：调用 `update()` 后再调用 `findPublishedById`，应返回新数据
3. `mvn test` 所有测试全绿

## 验收

- 新测试通过
- 现有 38 个测试全绿
