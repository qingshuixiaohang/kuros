# feat: Spring Cloud Gateway 统一入口（微服务演进切片 #9）

## Problem Statement

切片 #8 完成后，`kuros-backend` 已注册到 Nacos，但前端与冒烟脚本仍直连 backend 的 8080 端口。微服务体系缺少统一入口：无法做集中路由、无法在入口层观测流量，后续服务拆分时前端将被迫逐个感知新服务的地址。对标小哈书专栏的微服务演进路线，下一步基础设施是 API 网关。

## Solution

新增平级工程 `kuros-gateway/`（Spring Cloud Gateway，WebFlux 独立容器），作为系统的"正门"：

- Gateway 占用宿主 8080（前端 `NEXT_PUBLIC_API_BASE_URL` 默认值即 8080，**前端零改动**）；backend 宿主映射挪至 8090，保留调试直连通道。
- 显式路由 `lb://kuros-backend`，路径原样透传（`/api/v1/**`、`/actuator/**` 等不做任何改写）。
- Gateway 自身注册为 `kuros-gateway`（Nacos 服务名），冒烟脚本可查到它。
- 本切片只做"门"：Sentinel、CORS、鉴权等能力全部留在 backend，Gateway 不加任何新业务能力。

## User Stories

1. 作为前端开发者，我希望 API 基地址保持 `http://localhost:8080` 不变，以便引入网关后前端零改动。
2. 作为后端开发者，我希望 Gateway 通过 Nacos 服务发现路由到 `kuros-backend`，以便后端多实例水平扩容时无需改网关配置。
3. 作为运维角色，我希望 Gateway 只暴露 `/actuator/health` 与 `/actuator/prometheus`，以便收窄网关的攻击面。
4. 作为开发者，我希望保留 backend 的 8090 直连端口映射，以便绕过网关调试定位问题。
5. 作为开发者，我希望 Nacos 不可用时 Gateway 仍能以本地配置启动，以便本地开发不被基础设施阻塞。
6. 作为学习者，我希望通过本切片理解 WebFlux 网关、谓词路由、lb 负载均衡的原理与生产实践。

## Implementation Decisions（Grill 三轮收口，共 12 项）

### 第一轮：端口 / 路由 / 边界

1. **端口策略（Q1-A）**：Gateway 容器监听 8080 并占宿主 8080 正门；backend 宿主映射改为 `${BACKEND_PORT:-8090}:8080`（容器内不变），实现前端零改动。
2. **路由配置（Q2-A）**：显式声明唯一路由 `lb://kuros-backend`，路径原样透传，不加 StripPrefix/RewritePath 等过滤器。实现注：原计划用 properties 索引写法，但 CI 实测 Boot 4.1 + SCG 4.3 下 predicates[0] 标量绑定失败（列表留空触发 @NotEmpty 拒绝启动），改为 GatewayRoutesConfig 编程式 DSL，决策意图不变。
3. **Sentinel 归属（Q3-A）**：留 backend，本切片只做"门"，不引入网关限流。
4. **CORS 归属（Q4-A）**：留 backend，避免网关与后端重复追加 CORS 头。
5. **backend 端口暴露（Q5-A）**：保留 8090 宿主映射，作为调试直连通道。
6. **范围边界（Q6-A）**：纯换门。不新增鉴权、限流、灰度、日志脱敏等任何新能力。

### 第二轮：工程形态 / 注册 / 暴露

7. **工程形态（Q1-A）**：平级新项目 `kuros-gateway/`（与 kuros-backend 同级，独立 pom / Dockerfile），零重构成本。WebFlux 网关无法嵌入 Servlet 后端，必须独立容器。
8. **Nacos 注册（Q2-A）**：Gateway 注册服务名 `kuros-gateway`，与 `kuros-backend` 语义一致。
9. **Actuator 暴露（Q3-A）**：仅白名单 `management.endpoints.web.exposure.include=health,prometheus`（backend 现有四个端点，网关收窄到探测 + 指标两个）。

### 第三轮：测试 / 冒烟 / 配置中心

10. **集成测试缝（Q1-A）**：Testcontainers 起真实 nacos-server + 进程内桩 HTTP 服务器（`com.sun.net.httpserver.HttpServer`）模拟后端，秒级反馈，不起完整 compose。
11. **冒烟脚本深度（Q2-A）**：compose-smoke.mjs 仅多查一个 Nacos 服务名 `kuros-gateway`（复用现有 `verifyNacosRegistration` 的查询逻辑），不通过网关重复跑全量业务断言。
12. **配置中心接入（Q3-A）**：`spring.config.import=optional:nacos:kuros-gateway.properties`（optional 前缀，与 backend 同款分层迁移模式），本切片不往远端放真实覆盖配置。

## Testing Decisions

- **好测试标准**：只断言外部可观察行为——HTTP 转发结果、Nacos 注册表中存在 healthy 实例；不断言 Gateway 内部 Route/Handler 结构。
- **主接缝**（两个集成测试，TDD 先 RED 后 GREEN）：
  1. `GatewayRoutingIntegrationTest`：桩服务器模拟 backend + 显式路由，断言请求经网关后原样到达桩（Header/Cookie/查询串透传、响应原样返回）。不依赖 Nacos，秒级。
  2. `GatewayNacosDiscoveryIntegrationTest`：Testcontainers nacos-server（复用 `NacosContainers` 固定端口方案），断言 ① 网关注册为 `kuros-gateway` healthy 实例；② 桩实例手动注册进 Nacos 后，`lb://kuros-backend` 路由可经服务发现转发成功。
- **冒烟接缝**：compose-smoke.mjs 扩展 `verifyNacosRegistration` 同时校验 `kuros-backend` 与 `kuros-gateway` 两个服务名。
- **先例**：Slice #8 的 NacosDiscoveryIntegrationTest / NacosContainers 模式原样复用。

## Out of Scope

- 网关层限流（Sentinel Gateway 适配）、鉴权、CORS、灰度/金丝雀发布
- 路径改写（StripPrefix 等）、聚合响应、请求/响应改写
- 微服务拆分 / OpenFeign
- Gateway 集群部署 / 多环境 namespace
- 生产级配置（远端 dataId 覆盖值等真实环境差异配置，等产生需求再填充）

## Further Notes

- 版本策略沿用 Slice #8：Spring Cloud 2025.1.1 BOM（`spring-cloud-starter-gateway` 由其管理）+ SCA 2025.1.0.0（nacos-discovery/config）；Boot 4.1.1 minor 赌注已由 Slice #8 实测通过。
- 关键洞察：SaToken Cookie 为 Host-only 且跨端口共享，会话穿透网关几乎免费；媒体 URL 为响应时拼接的绝对路径，不受网关影响。
- 执行模式：耗时操作（mvn test、docker compose build、全量冒烟）由用户执行，AI 只做秒级操作（文件编辑、git、gh、探针）。
- 学习复盘文档产出至 docs/learning/09-gateway.md（切片惯例）。
