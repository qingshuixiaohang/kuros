# 切片 #8：Nacos 服务注册发现 + 配置中心

## 1. 架构迁移全景

### 迁移前
纯单体：所有配置硬编码在 application.properties（改任何配置都要重新打包），
应用不向任何注册中心注册，Gateway / 服务拆分无从谈起。

### 迁移后
- compose 新增 nacos 服务（v3.1.1 standalone），配置数据持久化到已有 MySQL
- 应用启动自动注册为临时实例（SPRING_CLOUD 来源，5s 心跳 / 15s 摘除）
- `spring.config.import=optional:nacos:kuros-backend.properties` 分层配置：
  本地保留完整 fallback，Nacos 按需覆盖
- Sentinel QPS 阈值支持不重启动态刷新（eager 路径）
- SpringDoc 开关通过 @RefreshScope 桥接 bean 可观察（lazy 路径）

### 变更清单

| 文件 | 变更 |
|---|---|
| `pom.xml` | Spring Cloud 2025.1.1 + SCA 2025.1.0.0 双 BOM + nacos discovery/config starters |
| `compose.yml` | nacos 服务 + MySQL init 目录挂载 + backend 硬依赖 nacos healthy |
| `application.properties` | config.import + server-addr + 兼容校验器关闭 |
| `SentinelRuleRefresher.java` | 新建：规则注册 + 刷新事件监听（eager） |
| `SpringDocStatusBridge.java` | 新建：@RefreshScope 桥接（lazy） |
| `SentinelConfig.java` | 瘦身：规则注册职责移交 refresher |
| `NacosDiscoveryIntegrationTest` | 注册链路集成测试（Testcontainers 真实 Nacos） |
| `NacosConfigRefreshIntegrationTest` | 覆盖 + 动态刷新集成测试 |

## 2. 关键难点解析（本切片全是坑，逐个记录）

### 2.1 版本校验器 ≠ 真实不兼容
Spring Cloud 2025.1.1 的 CompatibilityVerifier 直接拒绝 Boot 4.1.1（"not compatible"）。
但它只做**版本号字符串比对**，不代表二进制不兼容。处置：关闭校验器
（`spring.cloud.compatibility-verifier.enabled=false`），用真实集成测试验证兼容性。
实测 Boot 4.1.1 + SCA 2025.1.0.0 全链路可用，省掉一次 Boot 降级。

### 2.2 客户端与服务端 minor 必须对齐
SCA 2025.1.0.0 官方文档写"内置 Nacos Client 3.0.0"，实际捆绑 **3.1.1**（BOM 内覆盖）。
服务端选 v3.0.3 时 gRPC 握手持续失败（`UNAVAILABLE: Network closed for unknown reason`），
对齐 v3.1.1 后一次通过。教训：**文档会撒谎，`mvn dependency:tree` 不会**。

### 2.3 gRPC 端口偏移 +1000 与 Testcontainers 随机映射
Nacos 客户端约定 gRPC 端口 = API 端口 + 1000（同一地址推导）。
Testcontainers 默认随机端口映射打破偏移量 → 客户端连不上 gRPC。
解法：`withCreateContainerCmdModifier` 固定绑定 28848/29848，保持差值。

### 2.4 Windows 上 localhost 解析为 IPv6
`localhost` 优先解析为 `[::1]`，Docker 端口映射对 IPv6 环回实测 `Permission denied: getsockopt`。
客户端地址一律写 `127.0.0.1`。

### 2.5 @DynamicPropertySource 晚于 config data 解析
`spring.config.import` 在环境准备阶段解析，此时 DynamicPropertySource 的属性源还没注册。
所以 Nacos 集成测试的 server-addr 必须用 `@SpringBootTest` 内联属性传递 ——
这就是容器必须固定端口（2.3）的连带原因：地址是编译期常量才能写进注解。

### 2.6 v3 镜像的鉴权语义
v3 镜像**强制要求** NACOS_AUTH_TOKEN / IDENTITY_KEY / IDENTITY_VALUE 三件套（否则拒绝启动），
但鉴权开关 `NACOS_AUTH_ENABLE` 默认关闭。全新实例没有任何用户（"User nacos not found"），
dev 环境保持关闭即可；生产硬化（admin 初始化 + 账号管理）留给后续切片。

### 2.7 MySQL 认证插件与公钥检索
`nacos` 用户用 MySQL 8 默认的 `caching_sha2_password`，非 SSL 首连要取服务器 RSA 公钥，
Nacos 镜像默认 JDBC 参数缺 `allowPublicKeyRetrieval=true` → 认证直接失败。
通过 `MYSQL_SERVICE_DB_PARAM` 环境变量补参数解决。

### 2.8 @RefreshScope 是懒重建，不是"改了就生效"
refresh 事件只清缓存实例，等下次有人访问才重建。规则注册发生在 bean 创建期
（@PostConstruct），挂 @RefreshScope 会导致"改了配置但没人访问就永远不生效"。
两种正确姿势（本项目各用一个）：
- **eager**：监听 RefreshScopeRefreshedEvent 重读 Environment（SentinelRuleRefresher）
- **lazy**：@RefreshScope 代理，适合状态观察类（SpringDocStatusBridge）

## 3. 简历 STAR 写法

**Situation**：单体应用配置硬编码、无服务注册，微服务演进缺少基础设施。

**Task**：引入 Nacos 作为注册中心 + 配置中心，为 Gateway 与服务拆分打地基，
并保证 Nacos 不可用时应用仍能以本地配置降级启动。

**Action**：集成 Spring Cloud Alibaba Nacos discovery/config；设计分层配置策略
（本地 fallback + 远端覆盖）；实现 Sentinel 阈值不重启动态刷新；用 Testcontainers
真实 Nacos 写注册/覆盖/刷新三类集成测试；排掉版本校验器误报、gRPC 端口偏移、
IPv6 解析、MySQL 认证插件四个集成坑。

**Result**：compose 一键起 6 服务，应用注册为健康临时实例；控制台改配置秒级生效；
冒烟脚本纳入注册发现端到端检查；为切片 #9 Gateway 的服务名路由铺平道路。

## 4. 原理详解

### 临时实例（ephemeral）心跳机制
注册发生在 `WebServerInitializedEvent`（真实 Tomcat 起来时；MockMvc 的 MOCK 环境
不启动服务器所以永远不会注册 —— 这就是集成测试用 RANDOM_PORT 的原因）。
- 客户端每 5s 发心跳（gRPC 长连接健康检查）
- 15s 无心跳标记不健康，30s 摘除
- 应用正常关闭主动注销；kill -9 靠超时摘除

### 配置中心长轮询（Long Polling）
客户端 30s 一次长轮询（服务端 hold 住 29.5s，有变更立即返回），
变更到达后客户端拉取新配置 → 发布 RefreshEvent → ContextRefresher 刷新 Environment
→ RefreshScope 清缓存 + 触发事件监听。比短轮询省请求，比 WebSocket 简单可靠。

### spring.config.import 的优先级
config data import 的属性源**高于** application.properties —— 这正是"本地 fallback、
远端覆盖"的实现机制：同名 key 远端命中，本地不再生效。`optional:` 前缀保证
Nacos 不可用时跳过导入而非启动失败。

## 5. 面试八股文整理

**Q: Nacos 如何感知服务下线？**
A: 分两种。临时实例靠心跳/gRPC 连接断开感知（秒级）；永久实例靠服务端主动健康检查，
不健康只标记不摘除。默认注册的都是临时实例。

**Q: Nacos 配置变更是推还是拉？**
A: 长轮询 —— 微妙上的"推拉结合"：客户端拉起长轮询，服务端 hold 到有变更或 29.5s 超时，
变更时立即响应（准实时），客户端再拉全量配置。单纯推需要维护长连接状态，单纯拉延迟高。

**Q: 为什么配置刷新有的用 @RefreshScope 有的用事件监听？**
A: @RefreshScope 是懒重建语义，适合"下次访问时取新值"的状态 bean；
需要"改完立即执行副作用"（如重新 loadRules）的场景必须监听刷新事件主动处理。

**Q: Nacos 与 Eureka 的区别？**
A: Eureka AP 系统、只支持临时实例靠心跳、已停止大版本演进；Nacos 同时支持 CP（Raft，
持久实例）和 AP（Distro，临时实例），自带配置中心，国内生态（SCA）一等公民。

## 6. 技术选型对比

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| SCA starter（选它） | 声明式集成、自动配置、社区文档全 | 版本矩阵强约束（校验器） | 采用 + 校验器关闭 |
| 原生 nacos-client SDK | 无版本约束 | 手写注册/刷新生命周期，侵入大 | 备选（SCA 真不兼容再换） |
| Nacos 部署 Derby 内嵌 | 零依赖 | 配置数据不可见、难备份 | 弃 |
| Nacos 部署复用 MySQL | 数据可见可备份、少一个容器 | 共享库故障域 | 采用（学习项目够用） |

## 7. 面试叙事模板

> 30 秒版：
> 我给单体 Spring Boot 项目引入了 Nacos 作为注册中心和配置中心，Boot 4.1 比官方
> 适配的 4.0 高一个 minor，我用"关掉版本校验器 + 真实集成测试验证"的方式赌赢了这个
> 兼容性，避免了整体降级。配置做成分层覆盖：本地全量兜底，Nacos 按需覆盖，
> Sentinel 阈值做到改配置不重启生效。中间踩了 gRPC 端口偏移、客户端服务端版本
> 对齐这些坑，都沉淀成了集成测试。

> 2 分钟版：在 30 秒版基础上展开 2.2 / 2.3 / 2.8 三个坑的细节，
> 以及长轮询和心跳机制的原理（第 4 节）。
