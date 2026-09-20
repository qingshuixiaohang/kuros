# Ticket: PostPublishingService 埋点集成

**依赖**：ticket-01
**阻塞**：ticket-04

## 范围

1. `PostPublishingService.publish()` 注入 Counter 和 Timer
2. 每次发布调用 `counter.increment()`
3. 发布逻辑包裹在 `timer.record(() -> { ... })` 中记录耗时

## 验收

- 发布帖子后 `/actuator/metrics/posts.published.total` 数值递增
- `/actuator/metrics/posts.publish.duration` 有耗时记录
