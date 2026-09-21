# Ticket: Nacos 基础设施 + 服务发现接入

**父 Issue**：#64（切片 #8：Nacos 服务注册发现 + 配置中心）
**依赖**：无
**阻塞**：nacos-02

## 范围

1. `pom.xml` 新增 `<dependencyManagement>`：Spring Cloud BOM 2025.1.0 + Spring Cloud Alibaba BOM 2025.1.0.0（Boot 4.1.1 minor 兼容赌注，启动失败再降级）；引入 `spring-cloud-starter-alibaba-nacos-discovery` + `nacos-config` starter
2. `compose.yml` 新增 nacos 服务：standalone 模式、数据持久化到已有 MySQL（需新增 init SQL 建 nacos 库表）、端口映射 8848/9848 + 控制台 8081:8080（避开 backend 8080）
3. `application.properties` 添加 `spring.config.import=optional:nacos:kuros-backend.properties` + Nacos server-addr 配置（Boot 4 已废弃 bootstrap 机制，必须用 config.import）
4. `backend` depends_on nacos `condition: service_healthy`（fail-fast 由编排层承担）
5. Testcontainers 集成测试（GenericContainer 起真实 nacos-server）：应用启动后查询 Nacos 注册表，断言存在 healthy 的 `kuros-backend` 实例（TDD：先 RED 后 GREEN）

## 验收

- `mvn test` 全绿（含新注册集成测试）
- `docker compose up -d --build` 后 6 服务 healthy
- Nacos 控制台（localhost:8081）服务列表可见 `kuros-backend`，实例 healthy
- Sentinel `sentinel-core` 1.8.10 显式声明保留（Maven 最近优先覆盖 SCA 传递的 1.8.9）
