# Ticket: RocketMQ 基础设施与 binder 打通（切片 #11 · A-1）

**父 Issue**：#69（切片 #11：互动写路径异步化）
**依赖**：无（可立即开始）
**阻塞**：int-03

## 范围

1. `kuros-backend/pom.xml` 加 `spring-cloud-starter-stream-rocketmq`（`com.alibaba.cloud`，SCA BOM 管版本）；`mvn dependency:tree` 确认捆绑的 rocketmq-client 版本，若触发 Spring Cloud 兼容校验失败则沿用先例 `spring.cloud.compatibility-verifier.enabled=false`
2. compose 新增三服务（8 → 11）：`rocketmq-namesrv`（`apache/rocketmq:5.3.x`，`sh mqnamesrv`，9876）、`rocketmq-broker`（同镜像，`sh mqbroker -n rocketmq-namesrv:9876`，配 `brokerIP1` + `autoCreateTopicEnable=true`，端口 10909/10911/10912，depends_on namesrv healthy）、`rocketmq-dashboard`（`apacherocketmq/rocketmq-dashboard`，宿主 8082 避开 gateway 8080/nacos 8081，`JAVA_OPTS=-Drocketmq.namesrv.addr=rocketmq-namesrv:9876`）；Docker Hub 受限优先 quay.io
3. `application.properties`：`spring.cloud.stream.rocketmq.binder.name-server`、一个最小 output/input binding（destination 如 `interaction-ping-topic`）、`spring.cloud.function.definition`
4. 最小 tracer bullet：一个 `StreamBridge` 发送 ping 事件 + 一个 `Consumer<String>` 接收，证明 producer→broker→consumer 管道贯通（domain 事件在 int-03 替换 ping）
5. 验收：`docker compose config` 通过；backend 携 binder 启动、ping 事件 round-trip（Testcontainers 或 compose 冒烟，**耗时交用户/CI**）

## 验收

- [ ] `mvn dependency:tree` 显示 rocketmq binder + client；backend 编译通过
- [ ] `docker compose config` 校验通过，11 服务定义完整（namesrv/broker/dashboard 端口与 depends_on 正确）
- [ ] backend 携 RocketMQ binder 启动不报错；最小 ping 事件 producer→consumer round-trip（集成验证交用户/CI）

## 备注

- 本 ticket 只交付「基础设施 + 管道贯通」，不含互动业务逻辑（垂直切片从 int-03 起接入 domain 事件）
- name-server 在 compose 网络内用服务名 `rocketmq-namesrv:9876`；本地/测试用 Testcontainers 动态地址
