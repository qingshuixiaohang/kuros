# feat: Nacos 服务注册发现 + 配置中心（微服务演进切片 #8）

## Problem Statement

kuros-backend 目前是纯单体：所有配置（数据源、Redis、MinIO、SaToken、Sentinel 阈值等）硬编码在 application.properties 中，修改任何配置都需要重新打包部署；应用也不向任何注册中心注册，无法被后续引入的 Gateway / 拆分服务发现。微服务演进路线（对标小哈书专栏）的下一步基础设施缺失。

## Solution

引入 Nacos 作为服务注册中心 + 配置中心。应用启动时注册到 Nacos；环境相关配置分层迁移——application.properties 保留完整 fallback 默认值，Nacos 中的 `kuros-backend.properties` 按需覆盖，Nacos 不可用时应用仍能以本地值启动。Sentinel QPS 阈值与 SpringDoc 开关支持 Nacos 动态刷新（改配置不重启立即生效）。

## User Stories

1. 作为后端开发者，我希望应用启动时自动注册到 Nacos，以便后续 Gateway 和拆分服务能发现我。
2. 作为后端开发者，我希望数据源、Redis、MinIO 等环境配置能从 Nacos 配置中心读取，以便不改代码就能切换环境。
3. 作为后端开发者，我希望 Nacos 宕机时应用仍能以本地 application.properties 启动，以便本地开发不被基础设施阻塞。
4. 作为运维角色，我希望在 Nacos 控制台修改 Sentinel QPS 阈值后无需重启即生效，以便线上突发流量时快速调整限流。
5. 作为运维角色，我希望在 Nacos 控制台动态开关 SpringDoc/Swagger UI，以便生产环境关闭 API 文档暴露。
6. 作为后端开发者，我希望 Nacos 配置持久化到已有 MySQL，以便容器重建后配置不丢失。
7. 作为学习者，我希望通过本切片理解注册发现与配置中心两大 Nacos 核心能力的原理与生产实践。
8. 作为开发者，我希望 compose 一键启动时 Nacos 与 backend 的启动顺序由健康检查保证，以便全栈自愈。
9. 作为开发者，我希望 Actuator 健康检查反映 Nacos 连接状态，以便快速定位注册中心故障。

## Implementation Decisions

1. **版本策略**：保持 Spring Boot 4.1.1，引入 Spring Cloud BOM 2025.1.0 + Spring Cloud Alibaba BOM 2025.1.0.0（官方适配 4.0.0，minor 差异赌注，启动失败再降级）。
2. **依赖引入**：`spring-cloud-starter-alibaba-nacos-discovery` + `spring-cloud-starter-alibaba-nacos-config`；通过新增 `<dependencyManagement>` 节管理两个 BOM（当前 pom 无 BOM 管理）。
3. **Sentinel 版本**：保留现有 `sentinel-core` 1.8.10 显式声明，Maven 最近优先原则覆盖 SCA 传递依赖（1.8.9），无 API 冲突。
4. **配置导入方式**：Spring Boot 4 已废弃 bootstrap 机制，使用 `spring.config.import=optional:nacos:kuros-backend.properties`；optional 前缀保证 Nacos 离线时应用以本地 fallback 启动。
5. **分层配置**：application.properties 保留全部现有默认值不动；Nacos dataId `kuros-backend.properties`（public namespace + DEFAULT_GROUP）初始只放 2 组覆盖值做演示——Sentinel QPS 阈值 + SpringDoc 开关。
6. **配置格式**：properties 格式（与本地文件一致，迁移零翻译成本）。
7. **动态刷新**：仅 `SentinelConfig`（4 个 QPS 阈值 @Value）与 SpringDoc 开关挂 @RefreshScope；存储策略（@ConditionalOnProperty Bean 创建语义）明确不加 @RefreshScope。
8. **环境隔离**：不引入 Spring profile / 自定义 namespace，等 Slice #9 Gateway 或服务拆分时再升级。
9. **Nacos 部署**：compose.yml 新增 nacos 服务，standalone 模式，持久化复用现有 MySQL（新增 init SQL：nacos-mysql.sql 建库建表），端口映射 8848/9848 + 控制台 8081:8080（避开 backend 8080）。
10. **启动顺序**：backend `depends_on` nacos `condition: service_healthy`（fail-fast 由编排层承担）。
11. **服务名**：`spring.application.name=kuros-backend`（已存在），注册服务名即 `kuros-backend`。

## Testing Decisions

- **好测试标准**：只断言外部可观察行为（注册表中存在 healthy 实例、配置值来源、刷新后值变化），不断言 Nacos client 内部 API 调用细节。
- **主接缝**：Spring 上下文启动 + Testcontainers `GenericContainer` 起真实 nacos-server——① 启动后查询 Nacos 注册表断言 `kuros-backend` 实例已注册；② Nacos 预置覆盖配置，断言 Sentinel 阈值读取的是 Nacos 值而非本地默认；③ 测试中通过 Nacos API 发布新配置，断言 @RefreshScope bean 在刷新后返回新值。
- **冒烟接缝**：compose-smoke.mjs 扩展——nacos 控制台可达 + backend healthy + backend 已注册（通过 Nacos OpenAPI 查询）。
- **先例**：Slice #2 MinIO Testcontainers 模式（Testcontainers 2.x，模块坐标带 testcontainers- 前缀）。

## Out of Scope

- Spring Cloud Gateway（Slice #9）
- Sentinel Dashboard 与规则持久化到 Nacos（本切片只做 QPS 阈值动态刷新演示）
- 微服务拆分 / OpenFeign 服务间调用
- Nacos 集群模式、鉴权（auth.enable）、TLS
- 多环境 profile / 自定义 namespace / 灰度发布
- @ConfigurationProperties 重构（18 处 @Value 保持现状）

## Further Notes

- 兼容性风险预案：若 SCA 2025.1.0.0 在 Boot 4.1.1 上启动失败（自动配置类缺失/NoClassDefFound），回退方案是降 Boot 至 4.0.x 或改用 nacos-client 直连。
- Nacos 3.x 控制台默认端口 8080 与 backend 冲突，compose 中映射为宿主 8081。
- 学习复盘文档产出至 docs/learning/（切片惯例）。
