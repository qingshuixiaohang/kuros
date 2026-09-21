# feat: 服务拆分——用户域独立微服务（微服务演进切片 #10）

## Problem Statement

切片 #9 完成后，网关统一了入口，但 `kuros-backend` 仍是一个单体：Gateway 的 `lb://kuros-backend` 只有单一上游，"服务发现 + 统一入口"的架构价值没有兑现（负载均衡无第二个实例可均衡）。用户域（认证、验证码、会话、RBAC、公开资料、关注关系）与内容域（帖子、评论、互动、举报、媒体）耦合在同一个进程、同一个数据库、同一套实体引用中，任何一侧的变更都影响另一侧。

**难点叙事（为什么拆）**：单体内跨域调用零成本——内容域 6 处直接引用 `CommunityUserRepository`，`ProfileService` 一个类注入四个域的仓储，边界只能靠自觉；发布全量耦合（改一侧触发整体构建与全量回归）；高频认证与重 IO 内容共享进程资源，故障与选型互相连带。前置积木（#1 共享会话 / #8 注册发现 / #9 网关）已就绪，此刻具备拆分条件（缺一块，拆完即断）。

**方案选型**：保持模块化单体（包隔离仅是编译期可见，跨域调用依旧零成本，停在半步）✗｜一次性整体拆 6 服务（爆炸半径过大、过早优化）✗｜**渐进式拆分：先拆边界最清晰、被全站依赖的用户域，把"路由/发现/独立库/Feign/会话共享"验证成可复制模板** ✓。先拆"被依赖方" → 依赖方向天然单向（内容域 → 用户域）；反例（先拆内容域）会制造双向依赖。

**拆后收益与验证方式（可复现演示，不做无依据表述）**：边界物理化（跨域只能经 Feign 接口）｜独立部署（单独重启 kuros-user 不中断内容服务）｜故障隔离（停 user 后帖子列表降级返回、整页不崩）｜会话穿透（经网关登录写 Redis → 发帖读同一会话）。性能类收益留待压测切片以数据佐证。

对标小哈书专栏的服务拆分章节，本切片完成第一个真正的业务微服务拆分：**用户服务（kuros-user）**，让网关的 `lb://` 第一次拥有两个不同服务的路由目标。

## Solution

分两阶段实施：

**Phase A（重排）**：`kuros-backend` 内部从"按技术层分包"（api/config/domain/web/service）重排为"按业务模块分包"（user/post/comment/interaction/report/media/shared），纯移动、零行为变化、全量测试绿为验收标准。

**Phase B（拆分）**：新建平级工程 `kuros-user/`（认证 + 验证码 + RBAC + 用户资料实体 + 关注关系），独立数据库 `kuros_user`；backend 保留组合视图端点，经 **OpenFeign** 向 kuros-user 取用户数据（依赖单向：内容域 → 用户域）；Gateway 按 API 前缀分流；冒烟脚本跑通跨服务链路（经网关登录 → 发帖 → 详情返回作者昵称）。

## User Stories

1. 作为前端开发者，我希望所有 API 路径、Cookie、CSRF 契约保持完全不变，以便服务拆分对前端零改动。
2. 作为社区用户，我希望登录、关注、查看资料的体验与拆分前完全一致。
3. 作为社区用户，我希望发帖后在列表和详情中仍能看到作者昵称与头像（由内容域经服务间调用获取）。
4. 作为社区用户，我希望用户服务暂时不可用时帖子列表仍可阅读（作者信息降级占位，而非整页报错）。
5. 作为后端开发者，我希望内容域与用户域的表和代码彻底分离，各自独立演进、独立部署。
6. 作为后端开发者，我希望通过 OpenFeign 声明式调用 + Nacos 服务发现完成服务间通信，不在代码中硬编码地址。
7. 作为后端开发者，我希望登录态在两个服务间天然共享（同一 Redis + 同名 Cookie），无需任何迁移或同步逻辑。
8. 作为运维角色，我希望 `docker compose up` 一键启动全部 8 个服务（新增 kuros-user），健康检查依赖链完整。
9. 作为运维角色，我希望 kuros-user 提供独立的健康检查、Prometheus 指标与 Nacos 配置中心接入，运维面与 backend 对齐。
10. 作为开发者，我希望保留 kuros-user 的 8091 宿主直连端口用于调试，本地联调统一走网关 8080。
11. 作为 CI 维护者，我希望新增 User service tests job，新工程从第一天起纳入持续验证。
12. 作为冒烟脚本维护者，我希望验证 Nacos 中三个服务（backend/gateway/user）全部注册 healthy。
13. 作为冒烟脚本维护者，我希望一条端到端链路验证会话共享（登录打到 user、发帖打到 backend）+ 服务间调用（backend 经 Feign 取作者）。
14. 作为学习者，我希望通过本切片理解：拆分边界的判定、组合视图与依赖方向、跨库外键消失的后果、Feign 超时与降级。
15. 作为学习者，我希望重排阶段（Phase A）可独立验证与回退，拆分阶段（Phase B）出现问题时可二分定位是"重排"还是"拆分"引入。
16. 作为简历作者，我希望本切片产出"从单体拆出第一个微服务"的完整叙事：边界、数据、通信、容错、验证。

## Implementation Decisions（Grill 三轮收口，共 19 项）

### 第一轮：边界 / 阶段 / 通信（8 项）

1. **服务边界（Q1-B）**：kuros-user 包含认证、验证码、RBAC（四张表 + StpInterface）、用户资料实体、关注关系（UserFollow）；内容域全部留在 backend。
2. **两阶段实施（Q2-A）**：Phase A 按模块重排（独立 commit，全量测试绿验收）→ Phase B 真拆；两阶段各自可回退。
3. **服务间通信（Q3-A）**：OpenFeign（`spring-cloud-starter-openfeign`，2025.1 线为 5.0.0，**已查证未改名**）+ `spring-cloud-starter-loadbalancer`（Nacos 不传递）；超时 connect 1s / read 2s。
4. **数据库拆分（Q4-A）**：同一 MySQL 实例、独立 database `kuros_user`（compose mysql-init 增加建库脚本）；**历史迁移 V1-V9 不可改**，backend 新增 V10 DROP 迁走的表；历史链自洽（全新库从头执行仍合法）。
5. **公共代码共享（Q5-A）**：不建 common 模块，有纪律的复制（rule of three：第三处重复出现时再抽公共模块）。
6. **网关路由（Q6-A）**：`/api/v1/auth/**`、`/api/v1/users/*/follow` → `lb://kuros-user`；其余 `/**` → `lb://kuros-backend`。
7. **端口策略（Q7-A）**：kuros-user 容器内 8080、宿主映射 8091；compose 新服务 + healthcheck 依赖链；CI 新增第 5 个 job。
8. **会话共享（Q8-A）**：kuros-user 连同一个 Redis + 相同 SaToken 配置（同名 Cookie `KUROS_SESSION`），登录态零迁移——切片 #1 分布式会话的远期回报。

### 第二轮：组合视图 / 数据 / 容错 / 重排 / 测试 / 部署（6 项）

9. **组合视图归属（Q9-A）**：`/users/{id}`（公开资料）、`/users/{id}/posts`、`/users/me/profile` 留在 backend 组合（内容统计来自内容域、用户/关注数据经 Feign 取）；依赖单向 **内容域 → 用户域**，不制造双向依赖。
10. **迁移与基线（Q10-A）**：kuros-user 独立 Flyway（V1 建表 + V2 seed）；backend V10 DROP 用户域表；死代码 `UserSession`/`UserSessionRepository` 删除不迁移（全仓库无引用，ADR 0002 曾注明"拆分稳定后清理"——本切片兑现）。
11. **内部 API 契约与容错（Q11-A）**：kuros-user 提供内部接口（批量用户查询、关注状态/计数），**不经网关**、暂不鉴权（代码注释标注"生产需 mTLS 或内部 token"）；Feign 失败降级：作者信息占位（昵称"用户"、空头像），帖子列表/详情照常返回；资料页返回 503。
12. **Phase A 模块划分（Q12-A）**：7 模块——`user`（认证/资料/关注/RBAC/验证码/StpInterface）、`post`、`comment`、`interaction`、`report`、`media`（含 storage）、`shared`（config/exception/health/公共结构/DistributedLock）。
13. **测试策略（Q13-A）**：kuros-user 自带 Testcontainers 测试（复用 NacosContainers 固定端口模式）；backend 侧 Feign 依赖用 JDK HttpServer 桩 + `spring.cloud.openfeign.client.config.*.url` 属性直连（秒级）；保留一个真实 Nacos 发现链路的集成测试。
14. **部署与冒烟（Q14-A）**：冒烟注册校验升级为三服务；新增跨服务端到端链路（经网关登录打到 user 服务写 Redis → 发帖打到 backend 读同一 Redis 会话 → 详情作者昵称经 Feign 取得）。

### 第三轮：横切 / 种子 / 缓存 / 调试 / 守护（5 项）

15. **横切能力清单（Q15-A）**：kuros-user 复制 SaToken 配置（自己的白名单子集）、CSRF 拦截器（follow 为写端点）、actuator + micrometer-prometheus、Nacos 配置中心接入（`optional:nacos:kuros-user.properties`）；**不引入** Sentinel 限流、OpenAPI/Swagger（高流量/对外端点出现时再议）。
16. **种子一致性（Q16-A）**：kuros-user V2 seed 复制测试用户与 RBAC 数据，**ID/手机号与 backend 完全相同**（帖子 author_id 裸引用才能对齐）；跨库一致性靠"约定 ID + 服务接口"。
17. **缓存一致性（Q17-A）**：接受 backend Caffeine `publicProfile` 缓存 60s TTL 兜底（昵称修改最多 60s 旧数据；当前无昵称编辑功能）；代码注释标注"生产需事件驱动失效"（留给 RocketMQ 切片）。
18. **调试行为（Q18-A）**：接受直连 8090 时 user 端点 404（本地调试统一走网关 8080，更接近真实链路）；README/HANDOFF 注明。
19. **架构守护（Q19-A）**：暂不引入 ArchUnit；边界靠包结构显式化 + review 人守（ArchUnit 列为后续独立小切片候选）。

## Testing Decisions

- **好测试标准**：只断言外部可观察行为——HTTP 响应契约、Nacos 注册状态、跨服务链路结果；不断言内部包结构、Feign 客户端实现细节。
- **接缝（复用既有接缝优先，本切片新增一个）**：
  1. **kuros-user 工程内**（新工程自带）：Testcontainers MySQL + Redis 跑认证/关注/资料的行为测试；`NacosContainers` 固定端口模式复制过来跑注册发现测试。
  2. **backend 侧 Feign 桩**（新接缝，复用 Gateway 切片 StubBackend 经验）：JDK `com.sun.net.httpserver.HttpServer` 模拟 kuros-user，`@DynamicPropertySource` 覆盖 Feign url 属性直连桩；秒级反馈，覆盖"作者信息组装 / 批量查询 / 降级占位"。
  3. **backend Nacos 真实链路**（保留一个）：Testcontainers nacos-server + 手动注册桩实例为 `kuros-user`，验证 `lb://` 服务发现 + Feign 端到端。
  4. **冒烟接缝**：compose-smoke.mjs 三服务注册校验 + 跨服务链路（登录→发帖→作者昵称）。
- **先例**：Slice #8 `NacosContainers`/`NacosDiscoveryIntegrationTest`、Slice #9 `StubBackend`/`GatewayRoutingIntegrationTest` 模式原样复用。

## Out of Scope

- ArchUnit 架构守护测试（后续独立小切片）
- RocketMQ 事件驱动的跨服务缓存失效
- Nacos 生产鉴权硬化（独立切片）
- Sentinel 限流下沉到 user 服务 / 网关限流
- OpenAPI 文档进 user 服务
- 拆出第二个业务微服务（帖子/评论域）
- 多实例部署与真实负载均衡压测（Jmeter 切片）
- 昵称/头像编辑功能（当前产品没有，缓存失效场景不成立）

## Further Notes

- **版本策略**：沿用 Slice #8/#9 组合（Boot 4.1.1 + Spring Cloud 2025.1.1 + SCA 2025.1.0.0）；OpenFeign starter 名称已查证无改名风险，但需留意 2025.1 线 4.x → 5.0 的 AOT/急切属性解析变化（`spring.cloud.openfeign.lazy-attributes-resolution`）。
- **关键风险**：跨库无外键后数据一致性从数据库约束降级为服务契约——seed 对齐、降级占位、注释标注是本切片的三道防线；这也是面试可深挖的架构讨论点。
- **执行模式**：完全自主开发（AI 执行依赖拉取、mvn test、compose 构建等耗时操作），CI 作为权威验证证据。
- **产出物**：spec（本文档）+ ADR [0003](../adr/0003-user-service-split.md) + CONTEXT.md 更新 + 学习复盘 `docs/learning/10-service-split.md`（切片惯例）。
