# 切片 #9：Spring Cloud Gateway 统一入口

## 1. 架构迁移全景

### 迁移前
前端与冒烟脚本直连 backend 的 8080 端口：微服务体系有注册中心（切片 #8）但没有
统一入口，后续服务拆分时前端要逐个感知新服务地址，入口层也无法集中观测流量。

### 迁移后
- 新增平级工程 `kuros-gateway/`（Spring Cloud Gateway Server WebFlux 独立容器），
  占宿主 8080 正门；backend 宿主映射挪至 8090 保留调试直连
- 唯一路由 `lb://kuros-backend`，`Path=/**` 路径原样透传，无任何改写过滤器（纯换门）
- 网关注册 Nacos 服务名 `kuros-gateway`；`spring.config.import=optional:nacos:`
  分层配置与 backend 同款
- Actuator 白名单仅 `health` + `prometheus`
- Sentinel / CORS / 鉴权全部留在 backend——本切片只做"门"

### 变更清单

| 文件 | 变更 |
|---|---|
| `kuros-gateway/` | 新建平级工程：pom（双 BOM + gateway-server-webflux + loadbalancer + nacos starters）、启动类、Dockerfile |
| `kuros-gateway/application.properties` | 唯一路由 + nacos 分层导入 + actuator 白名单 |
| `compose.yml` | gateway 服务（8080 正门）+ backend 挪 8090 + frontend 等 gateway healthy |
| `compose-smoke.mjs` | `verifyNacosRegistration` 扩展为校验 backend + gateway 两个服务名 |
| `.env.example` | `GATEWAY_PORT=8080` / `BACKEND_PORT=8090` 端口约定 |
| `GatewayRoutingIntegrationTest` | 桩后端转发测试（路径/查询串/Cookie/body/状态码原样透传） |
| `GatewayNacosDiscoveryIntegrationTest` | 注册链路 + lb 服务发现转发测试（Testcontainers 真实 Nacos） |

## 2. 关键难点解析

### 2.1 WebFlux 网关必须独立容器
kuros-backend 是 Servlet（Tomcat）栈。Spring Cloud Gateway 基于 WebFlux/Netty，
class 下同时有 spring-webmvc 与 gateway 会导致启动失败（Boot 要求二选一的 Web 栈）。
所以网关不能做成 backend 的一个模块，只能是平级独立工程——这也天然满足
"零重构成本"的工程形态决策。

### 2.2 SCG 4.3 的 starter 与属性前缀双双改名
- starter：`spring-cloud-starter-gateway` → **`spring-cloud-starter-gateway-server-webflux`**
  （WebFlux 与 MVC 两种实现栈拆分命名）
- 路由属性：`spring.cloud.gateway.routes` → **`spring.cloud.gateway.server.webflux.routes`**
  （旧前缀在新版本绑定失败）

教训：Spring Cloud 2025.x 时代的组件命名从"全家桶单一坐标"转向"栈 + 实现后缀"，
老博客的坐标与配置不能直接抄。

### 2.3 lb:// 路由不自动生效
`uri=lb://kuros-backend` 的解析依赖 `spring-cloud-starter-loadbalancer`
（ReactiveLoadBalancerClientFilter + ServiceInstanceListSupplier）。nacos-discovery
只提供 DiscoveryClient 数据源，不会传递 loadbalancer 依赖——缺了它启动后路由
直接 503。必须显式引入。

### 2.4 会话穿透几乎免费
SaToken 的会话 Cookie 是 Host-only（无 Domain 属性），浏览器对
localhost:8080（网关）与 localhost:8090（backend）一视同仁地携带；网关原样
转发 Cookie，backend 无感知。因此"纯换门"不需要在网关层做任何会话处理。

### 2.5 端口策略是前端零改动的关键
前端 `NEXT_PUBLIC_API_BASE_URL` 是**构建期注入**的，默认值即 8080。
把网关放在 8080 而不是给 backend 加 StripPrefix 转发前缀，前端与移动端
零改动完成切换；backend 宿主 8090 仅作调试直连通道。

### 2.6 集成测试的两条缝
- **桩服务器缝**：JDK 自带 `com.sun.net.httpserver.HttpServer` 模拟后端，
  路由 uri 用 `@DynamicPropertySource` 覆盖为桩地址——不起 Nacos，秒级。
- **服务发现缝**：Testcontainers 起真实 Nacos（固定端口方案与 backend 一致），
  测试内用 nacos-client 手动把桩注册为 `kuros-backend`，验证 lb 经服务发现
  的完整链路。注册发生在 `WebServerInitializedEvent`，所以必须 RANDOM_PORT。

## 3. 简历 STAR 写法

- **S**：微服务体系有注册中心但无统一入口，前端直连单体端口，服务拆分无从谈起
- **T**：引入 Spring Cloud Gateway 作为系统正门，要求前端零改动、后端能力不动
- **A**：平级 WebFlux 网关工程 + `lb://` 服务发现路由 + 端口策略重排
  （网关 8080 / 调试 8090）+ actuator 白名单收窄；桩服务器 + Testcontainers
  双缝集成测试覆盖转发与发现
- **R**：前端零改动切换到网关入口；Nacos 中 backend/gateway 双服务健康注册；
  为后续服务拆分与入口层流量治理打好地基

## 4. 原理详解

```
浏览器 ── Cookie: KUROS_SESSION ──▶ Gateway (Netty, :8080)
                                       │ RoutePredicateHandlerMapping
                                       │   Path=/** 命中 routes[0]
                                       │ ReactiveLoadBalancerClientFilter
                                       │   lb://kuros-backend
                                       │     ▼
                                       │ Spring Cloud LoadBalancer
                                       │     ▼ ServiceInstanceListSupplier
                                       │ NacosDiscoveryClient
                                       │     ▼ gRPC 订阅
                                       │ Nacos Server (实例列表)
                                       ▼
                              kuros-backend (Tomcat, :8080 容器内)
```

- **RoutePredicateHandlerMapping**：按谓词匹配路由，命中后取出 RouteDefinition
- **Filter 链**：默认过滤器 + lb 过滤器，把 `lb://` 换成真实实例地址
  （`http://ip:port`），再由 Netty HttpClient 转发
- **转发语义**：路径、查询串、Header、Cookie 原样透传（不加改写过滤器时）

## 5. 面试八股文整理

**Q：为什么选 Spring Cloud Gateway 而不是 Nginx / Zuul？**
A：Nginx 是静态反向代理，配置改后要 reload，且无法感知注册中心的实例变化；
Zuul 1.x 是 Servlet 阻塞 IO，吞吐差。Gateway 基于 WebFlux 非阻塞模型 +
与 Nacos/LoadBalancer 生态原生集成，路由可随服务发现动态生效——适合微服务体系
的"智能入口"。Nginx 适合做它前面的静态资源/SSL 卸载，二者不互斥。

**Q：Gateway 为什么基于 WebFlux？带来什么约束？**
A：网关是 IO 密集型（转发），非阻塞模型用少量线程扛高并发转发。约束：不能与
Servlet 栈共存于同一应用，必须独立部署；编程模型是响应式（本切片纯配置路由，
无自定义代码，未触及）。

**Q：lb:// 是怎么变成真实地址的？**
A：请求进入后，ReactiveLoadBalancerClientFilter 识别 scheme 为 lb，向
LoadBalancerClientFactory 取该服务的 ReactiveLoadBalancer，从
ServiceInstanceListSupplier（数据源是 DiscoveryClient，即 Nacos）拿实例列表，
选择一个实例替换 URI，再交给 Netty 转发。

**Q：网关层适合放什么，不适合放什么？**
A：适合——路由、统一鉴权入口、限流、灰度、日志埋点；不适合——业务逻辑。
本切片刻意"纯换门"：鉴权/CORS/限流留在 backend，网关职责单一，出问题排查面小；
等真正需要入口层治理时再逐项上移。

## 6. 技术选型对比

| 维度 | Spring Cloud Gateway | Nginx | Zuul 1.x |
|---|---|---|---|
| 编程模型 | WebFlux 非阻塞 | C epoll | Servlet 阻塞 |
| 服务发现感知 | 原生（DiscoveryClient） | 需Consul模板/手动 | 需 Ribbon |
| 动态路由 | 配置/代码，随注册表生效 | reload 配置文件 | 配置中心 |
| 过滤器生态 | 谓词+过滤器丰富，可 Java 扩展 | lua/模块 | filter 有限 |
| 学习对齐 | 小哈书专栏选型 | 运维向 | 已停维护 |

## 7. 面试叙事模板

**30 秒版**：我在微服务项目里引入 Spring Cloud Gateway 做统一入口：WebFlux
独立容器占 8080 正门，`lb://` 路由经 Nacos 服务发现转发到 backend，前端因为
API 基地址就是 8080 完成零改动切换。测试上用进程内桩服务器验证透传语义、
Testcontainers 真实 Nacos 验证发现链路。

**2 分钟版**：在 30 秒版基础上补充——为什么必须独立容器（Servlet 与 WebFlux
栈互斥）；SCG 4.3 的 starter 与属性前缀改名（老教程不可抄）；lb 依赖
loadbalancer starter 的隐性前提；端口策略（构建期注入的前端基地址决定网关必须
占 8080，backend 挪 8090 保留调试直连）；以及"纯换门"的边界决策——本切片
网关职责单一，为后续入口层治理（限流/灰度）留好位置而不急于引入。
