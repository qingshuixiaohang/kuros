# Ticket: 配置中心分层迁移 + 动态刷新

**父 Issue**：#64（切片 #8：Nacos 服务注册发现 + 配置中心）
**依赖**：nacos-01
**阻塞**：nacos-03

## 范围

1. `SentinelConfig` 4 个 QPS 阈值 `@Value` 挂 `@RefreshScope`（动态刷新演示核心场景）
2. SpringDoc 开关通过 `@RefreshScope` 桥接 bean 暴露当前状态（springdoc 自动配置启动期读取 Environment，直接刷新无效，需桥接）
3. Nacos 初始覆盖配置预置：dataId `kuros-backend.properties`（public namespace + DEFAULT_GROUP），初始只放 Sentinel QPS 覆盖值 + SpringDoc 开关（properties 格式）
4. Testcontainers 集成测试：
   - Nacos 预置覆盖值 → Sentinel 阈值读取的是 Nacos 值而非本地默认
   - 测试中通过 Nacos API 发布新配置 → `@RefreshScope` bean 值变化（不重启）
5. 存储策略（@ConditionalOnProperty Bean 创建语义）明确不加 @RefreshScope，测试中无需覆盖

## 验收

- `mvn test` 全绿（覆盖读取 + 动态刷新两个新测试）
- Nacos 控制台可见 `kuros-backend.properties` 配置内容
- 本地停掉 Nacos 容器后 backend 仍能以 application.properties fallback 启动（optional: 语义）
- application.properties 现有默认值一行不动（分层迁移原则）
