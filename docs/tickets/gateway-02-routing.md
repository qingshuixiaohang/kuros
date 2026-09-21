# Ticket: 路由转发（lb://kuros-backend 原样透传）

**父 Issue**：#65（切片 #9：Spring Cloud Gateway 统一入口）
**依赖**：gateway-01
**阻塞**：gateway-03

## 范围

1. `application.properties` 显式声明路由：`spring.cloud.gateway.routes[0].id=kuros-backend`、`uri=lb://kuros-backend`、`predicates[0]=Path=/**`（路径原样透传，不加 StripPrefix/RewritePath 过滤器——Q2/Q6 决策）
2. 集成测试（TDD 先 RED）：`GatewayRoutingIntegrationTest`——`com.sun.net.httpserver.HttpServer` 桩服务器模拟 backend，路由 uri 直指桩地址，断言：
   - GET/POST 请求原样到达桩（路径、查询串、Cookie/Header 透传）
   - 桩响应（状态码、body、Content-Type）原样返回给客户端
3. `GatewayNacosDiscoveryIntegrationTest` 增补 lb 断言：测试内用 nacos-client 手动把桩实例注册为 `kuros-backend`，断言 `lb://kuros-backend` 路由经服务发现转发成功

## 验收

- 两个集成测试全绿
- 路由只此一条，无任何改写过滤器（纯换门，Q6 决策）

## 备注

- 桩服务器测试不依赖 Nacos，秒级反馈（Q1 决策：集成测试缝 = Testcontainers + 桩服务器）
- 会话穿透：SaToken Cookie 为 Host-only 跨端口共享，无需网关层任何处理（关键洞察）
