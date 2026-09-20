# Ticket: Sentinel 依赖 + SentinelConfig 配置类

**依赖**：无
**阻塞**：ticket-02, ticket-03

## 范围

1. `pom.xml` 添加 `sentinel-core:1.8.10` 依赖
2. 新建 `config/SentinelConfig.java`：`@ConditionalOnProperty` + `@PostConstruct` 注册 FlowRule
3. `application.properties` 添加 `app.sentinel.enabled=true` + 4 条 QPS 配置
4. `application-test.properties` 添加 `app.sentinel.enabled=false`

## 验收

- `mvn compile` 成功
- 现有测试不受影响（sentinel.enabled=false）
