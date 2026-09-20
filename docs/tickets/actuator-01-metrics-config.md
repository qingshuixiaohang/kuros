# Ticket: MetricsConfig + 自定义 Counter/Timer 指标

**依赖**：无
**阻塞**：ticket-02

## 范围

1. 新建 `config/MetricsConfig.java`：注入 `MeterRegistry`
2. 注册 Counter `posts.published.total`（帖子发布计数）
3. 注册 Timer `posts.publish.duration`（发布耗时）
4. `application.properties` 暴露 `health,info,metrics,prometheus` 端点

## 验收

- `mvn compile` 成功
- `/actuator/metrics` 可访问
