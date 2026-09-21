# Ticket: 添加 Caffeine 依赖 + CacheConfig 配置类

**依赖**：无
**阻塞**：ticket-02, ticket-03

## 范围

1. `pom.xml` 添加 `spring-boot-starter-cache` + `caffeine` 依赖（BOM 管理版本）
2. 新建 `config/CacheConfig.java`：`@EnableCaching` + `CaffeineCacheManager` Bean
3. `application.properties` 添加 `spring.cache.type=caffeine`
4. `application-test.properties` 添加 `spring.cache.type=none`（保护现有测试）

## 验收

- `mvn compile` 成功
- 现有 38 个测试不受影响（cache.type=none）
