# Ticket: Gateway 工程脚手架 + Nacos 注册

**父 Issue**：#65（切片 #9：Spring Cloud Gateway 统一入口）
**依赖**：无
**阻塞**：gateway-02

## 范围

1. 新建平级工程 `kuros-gateway/`：pom（parent spring-boot-starter-parent 4.1.1 + dependencyManagement 引入 Spring Cloud 2025.1.1 / SCA 2025.1.0.0 双 BOM）+ `spring-cloud-starter-gateway-server-webflux` + `spring-cloud-starter-alibaba-nacos-discovery` + `nacos-config` starter + actuator + test 依赖（testcontainers 2.0.5 / junit-jupiter）
2. 启动类 `KurosGatewayApplication`；`application.properties`：`spring.application.name=kuros-gateway`、`spring.config.import=optional:nacos:kuros-gateway.properties`、nacos server-addr/group、`management.endpoints.web.exposure.include=health,prometheus`（白名单决策）
3. `Dockerfile`（参照 kuros-backend：两段构建 + BuildKit m2 缓存 + 非 root 用户）+ `.dockerignore`
4. `compose.yml` 新增 gateway 服务：宿主 8080 正门、注册 Nacos、healthcheck 探 `/actuator/health`；backend 宿主映射改 `${BACKEND_PORT:-8090}:8080`；frontend `depends_on` 改为 gateway healthy
5. 集成测试（TDD 先 RED）：`GatewayNacosDiscoveryIntegrationTest`——Testcontainers nacos-server（复用 NacosContainers 固定端口方案），断言启动后 Nacos 注册表存在 healthy 的 `kuros-gateway` 实例

## 验收

- gateway 集成测试绿（注册断言通过）
- compose 7 服务 healthy；Nacos 控制台可见 `kuros-gateway`
- Gateway actuator 只暴露 health / prometheus（其余 404）

## 备注

- WebFlux 网关无法嵌入 Servlet 后端，必须独立容器（Grill 关键洞察 1）
- 兼容性赌注已由 Slice #8 验证（Boot 4.1.1 + SC 2025.1.1 + SCA 2025.1.0.0），gateway 工程同款组合
