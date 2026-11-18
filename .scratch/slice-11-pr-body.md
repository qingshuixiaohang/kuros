## 概述

切片 #11「互动写路径异步化 + RocketMQ」的代码落地。Closes #69。

## 改动摘要

### 架构核心（ADR 0004）

把「跨用户对热点帖计数行的 DB 行锁竞争」串行化到单队列单线程根除：
- 请求线程只写 Redis（状态 key + 计数）即时返回，不再同步 `UPDATE posts SET like_count = like_count + 1`
- 发互动事件到 RocketMQ（按 postId 分区顺序消息 orderly=true）
- 消费端顺序消费、事务内幂等落库（复合主键 + existsById 防重）+ 刷 DB 冗余计数列
- MQ 发送失败/未启用 → 同步降级落库（复用消费端同一段 `projection.apply`），互动绝不丢

### 文件清单

**主代码（新增）**
- `interaction/event/`：InteractionEvent(record) / InteractionType / InteractionKind / InteractionEventPublisher 接口 / RocketMqInteractionEventPublisher（@ConditionalOnProperty async=true） / NoopInteractionEventPublisher（async=false + matchIfMissing） / InteractionStreamConfig（Consumer<InteractionEvent> → projection::apply）
- `interaction/redis/InteractionRedisStore.java`：状态/计数两类 key + 两段 Lua 原子回填（COUNT_BACKFILL_DELTA / STATE_BACKFILL）防并发 miss 丢失更新；`flip` 仅在状态真正翻转时 ±1（重复点击幂等）
- `interaction/service/InteractionProjectionService.java`：消费端与降级共用同一落库入口（existsById 防重 + TransactionTemplate 自开自提交）+ @CacheEvict

**主代码（改造）**
- `PostInteractionService`：删除类级 @Transactional；`findPost` 改 Redis-first；写路径统一 `interact()`：锁 → `redisStore.flip` → publish 或降级 → 返回实时快照；降级路径 commit 先于 unlock（HANDOFF 陷阱 #7 根除）

**基础设施**
- `pom.xml`：新增 `spring-cloud-starter-stream-rocketmq`（SCA BOM 2025.1.0.0 管版本，实际捆绑 rocketmq-client 5.3.1 + spring-cloud-stream 5.0.1）
- `application.properties`：RocketMQ binder 完整配置段（binder name-server / function definition / consumer binding + orderly / producer binding + partition-key=postId）
- `application-test.properties`：`async.enabled=false` + 空 `function.definition` + 哑 name-server（测试 profile 不连 broker，从根上规避 binder 启动风险）
- `compose.yml`：新增 3 服务（rocketmq-namesrv / rocketmq-broker / rocketmq-dashboard, apache/rocketmq:5.3.1 与 client 严格对齐）+ volume + backend 软依赖
- `docker/rocketmq/broker.conf` + `.gitattributes`：broker 配置 + 强制 LF 换行（规避 CRLF 跨平台踩坑）

**测试**
- `InteractionAsyncIntegrationTest`（**确定性、无 broker**）：用 `@Primary RecordingPublisher` 顶替 Noop，开关切换 deliver=true/false，覆盖：
  - 投递成功 → 只改 Redis + 发事件，不同步写 DB（异步化本质）
  - 投递失败 → 锁内同步降级落库（互动不丢）
  - 消费端重复投递 → 幂等（DB 仅一行 + 计数只 +1）
  - LIKE→UNLIKE 顺序消费终态回到基线
  - 重复点击 Redis 与 DB 两侧均幂等
  - 事件契约（postId/userId/type）正确
- `InteractionRocketMQIntegrationTest`（**gated**，`@Tag("rocketmq-e2e")` + `@EnabledIfSystemProperty(kuros.it.rocketmq=true)`）：真实 namesrv+broker 容器，验证 send→顺序消费→DB 最终一致 + 顺序终态。需 `-Dkuros.it.rocketmq=true` 显式触发；默认 `mvn test` 不跑（保持主套件确定性）
- `KurosBackendApplicationTests`：新增游客互动快照断言（userId=null → liked/favorited=false，计数 3700/1200）；注释说明现有精确计数断言在 async=false 同步降级下仍成立（Redis 与 DB 逐步对齐）
- `src/test/resources/rocketmq/broker-test.conf`：brokerIP1=localhost + 固定端口 10911/10909（解决 RocketMQ 容器化测试经典 IP 不可达坑）

## 编译级验证（AI 完成）

- ✅ main compile：96 个源文件 BUILD SUCCESS（无新增 warning；修复了 `ValueOperations.set(K,V,long,TimeUnit)` Spring Data Redis 4 废弃 API → Duration 重载）
- ✅ test-compile：BUILD SUCCESS（包括 InteractionRocketMQIntegrationTest 的 docker-java API：`HostConfig.newHostConfig` / `PortBinding` / `withCreateContainerCmdModifier`）
- ✅ `docker compose config`：11 服务解析正常（rocketmq-namesrv / rocketmq-broker / rocketmq-dashboard 加入）

## 耗时测试（交用户 / CI）

按既定分工规则，以下由用户/CI 跑，AI 不主动执行：

```bash
# 主套件（async=false 同步降级路径，验证 Redis 读源 + 降级落库 + 幂等）
./mvnw test

# RocketMQ 端到端（真实 broker，需 Docker + 固定端口 10911/10909 可用）
./mvnw test -Dkuros.it.rocketmq=true

# compose 冒烟（全栈 11 服务）
docker compose up --build -d
```

## 不在本切片范围

- commentCount / viewCount 计数维护（已知缺陷，显式排除）
- 帖子详情 / 列表的计数读源 Redis 优先（留切片 #13）
- Redis↔DB 计数对账 / 校准任务（留 #13）
- 评论 / 关注写路径异步化
- RocketMQ 事务消息 / 消息轨迹平台 / 多 broker 集群
