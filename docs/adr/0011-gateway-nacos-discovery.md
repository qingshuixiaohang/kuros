# 0011. 通过 Gateway 统一入口，用 Nacos 发现服务和管理配置

状态：已接受（依据切片 #8、#9 和当前实现补录）

## 背景

前端原来直接访问内容服务。拆出用户服务后，如果由前端选择服务地址，登录、关注、发帖等 API 的调用方都要知道部署细节。把服务地址固定写进网关和 Feign 也能转发，但每次改变实例地址都要同步改调用方配置。

## 决策

例如访问 `/api/v1/posts` 时，Gateway 根据路径选中 `kuros-backend`，再由负载均衡客户端从服务发现拿到可用实例地址，最后直接把 HTTP 请求发给该实例。Nacos 提供地址，业务请求由 Gateway 转发。

- 浏览器继续使用同一个 API 入口。Gateway 优先把 `/api/v1/auth/**` 和 `/api/v1/users/*/follow` 转给用户服务，其余路径默认走内容服务。资料聚合页也使用 `/users` 路径，所以关注路由只匹配具体端点。
- 路由表明确声明哪些请求属于哪个服务。默认的 `lb://` URI 使用服务名，由负载均衡客户端从服务发现获取实例地址。内容服务也通过 OpenFeign 按服务名调用用户服务。
- Nacos 同时提供服务注册发现和配置中心。应用保留本地配置，并可选导入 Nacos 配置。配置读取能回落到本地值，依赖服务发现的默认路由仍需要可用实例。Compose 中三个 Java 服务都依赖 Nacos 健康检查。
- Gateway 负责路由，并处理跨域预检与响应头去重。业务鉴权与内容接口限流留在对应服务；服务拆分及内部接口鉴权见 [ADR 0003](0003-user-service-split.md)。

## 备选与代价

浏览器直连各服务会把服务边界暴露给客户端。网关加固定地址在单机下配置简单，但实例变更要同步更新配置。采用服务发现增加了 Nacos 的内存和启动依赖。当前 Compose 用单机 Nacos，比集群省资源，同时只有一个注册和配置中心实例。

## 验证入口

[网关路由](../../kuros-gateway/src/main/java/com/kuros/kurosgateway/GatewayRoutesConfig.java)、[网关配置](../../kuros-gateway/src/main/resources/application.properties)、[Compose](../../compose.yml)、[网关发现集成测试](../../kuros-gateway/src/test/java/com/kuros/kurosgateway/discovery/GatewayNacosDiscoveryIntegrationTest.java)。选型细节见 [Nacos 规格](../slice8-nacos-spec.md)和 [Gateway 规格](../slice9-gateway-spec.md)。
