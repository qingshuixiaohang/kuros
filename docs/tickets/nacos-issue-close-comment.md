## 验证结果（切片 #8 完成）

**Commit**: c81d16d（分支 codex/distributed-session-auth）

### nacos-01 服务发现接入
- compose 6 服务（mysql/redis/minio/nacos/backend/frontend）全部 healthy
- 应用注册为临时实例：`DEFAULT_GROUP@@kuros-backend`，`healthy:true`，来源 `SPRING_CLOUD`
- 控制台 http://localhost:8081 可达（v3 独立端口）

### nacos-02 配置中心 + 动态刷新
- 集成测试 3/3 通过（Testcontainers 真实 Nacos v3.1.1）：
  - `NacosDiscoveryIntegrationTest`：启动自动注册，client OpenAPI 可查健康实例
  - `NacosConfigRefreshIntegrationTest`：Nacos 预置覆盖本地默认（555≠100）；运行期发布新配置不重启即生效（999）+ @RefreshScope 桥接翻转

### nacos-03 冒烟 + 文档
- `compose-smoke.mjs` 新增 Nacos 控制台 readiness + 注册实例端到端检查，实测通过
- 学习复盘：docs/learning/08-nacos-discovery-config.md（版本校验器误报、客户端/服务端版本对齐、gRPC 端口偏移 +1000、Windows IPv6 解析、@DynamicPropertySource 时序、v3 鉴权语义、MySQL 认证插件、@RefreshScope 懒重建，8 个坑全记录）

### 版本兼容性赌注结论
Boot 4.1.1 + SCA 2025.1.0.0（bundled nacos-client 3.1.1）+ nacos-server v3.1.1 实测全链路可用，
未降级 Boot。CompatibilityVerifier 的拒绝仅为版本号比对，已关闭并以集成测试替代验证。
