# 切片 #11：互动写路径异步化——Redis 计数前置 + RocketMQ 顺序消息落库

> 本切片把点赞/收藏两类高频互动写路径从"同步写 MySQL"改造为"Redis 计数前置 +
> RocketMQ 顺序消息异步落库"。请求线程只写 Redis（状态 key + 计数）即时返回，
> 发互动事件到 `post-interaction-topic`（按 postId 分区顺序消息），消费端顺序
> 消费、事务内幂等落库——把**跨用户对热点帖计数行的 DB 行锁竞争串行化到单队列
> 单线程根除**。共 6 个工单（int-01~06），本文是收口复盘。

## 1. 架构改造全景

### 改造前（切片 #10 完成时）

帖子点赞/收藏的写路径是经典同步：锁 → 事务内 `UPDATE posts SET like_count =
like_count + 1`（行锁）→ 落关系表 → 返回。三个结构性矛盾：

1. **热点行竞争**：一个热门帖被多人同时点赞，多线程争同一行 `like_count` 的行锁，
   后到的线程排队等（MySQL InnoDB 行锁 + MVCC）。
2. **跨用户串行化缺失**：不同用户点同一帖，DB 层面无法串行化（每个请求独立事务），
   只能靠行锁互斥；并发越高，争抢越激烈。
3. **耦合无缓冲**：请求线程 = 事务线程 = 响应线程。任一环慢（DB 抖动/锁等待/索引扫描）
   直接拖慢响应；MQ 的"削峰/异步/重试/死信"价值零兑现。

### 改造后

- 请求线程只写 Redis（状态 key + 计数）即时返回（**即时反馈**）。
- 发互动事件到 RocketMQ（按 postId 分区顺序消息 `orderly=true`）——**同一帖子的互动
  严格 FIFO**，消费端单线程顺序消费，DB 行锁竞争被队列**结构性消除**。
- 消费端事务内幂等落库（复合主键 `@IdClass` + `existsById` 防重）+ 刷 DB 冗余计数列
  （`incrementLikeCount/decrementLikeCount`）。
- MQ 发送失败/未启用 → 同步降级落库（复用消费端同一段 `projection.apply`），
  **互动绝不丢**。
- 读路径 Redis-first：状态/计数两类 key + 两段 Lua 原子回填（COUNT_BACKFILL_DELTA /
  STATE_BACKFILL）防并发 miss 丢失更新；miss 时回源 DB 并回填。

### 变更清单（按工单）

| 工单 | 交付 | 关键文件 |
|---|---|---|
| int-01 | 基础设施：pom 加 SCA stream-rocketmq、compose 加 namesrv/broker/dashboard、application.properties/test.properties 配置段、docker/rocketmq/broker.conf + .gitattributes | pom.xml / compose.yml / application.properties / application-test.properties / docker/rocketmq/ |
| int-02 | Redis 读源：InteractionRedisStore（状态/计数两类 key + 两段 Lua 回填） | interaction/redis/InteractionRedisStore.java |
| int-03 | 事件契约：InteractionEvent(record) / InteractionType / InteractionKind / InteractionEventPublisher 接口 | interaction/event/ |
| int-04 | 写路径异步化：PostInteractionService 改造（删类级 @Transactional + Redis-first findPost + 统一 interact + 降级） + InteractionProjectionService（消费端与降级共用落库入口） + RocketMqInteractionEventPublisher + NoopInteractionEventPublisher + InteractionStreamConfig | interaction/service/ + interaction/event/ |
| int-05 | 测试：InteractionAsyncIntegrationTest（确定性无 broker）+ InteractionRocketMQIntegrationTest（gated e2e）+ KurosBackendApplicationTests 增游客快照 + broker-test.conf | interaction/ 测试包 + rocketmq/broker-test.conf |
| int-06 | 冒烟 + 学习复盘 + Issue 关闭 | 本复盘 + PR #70 |

## 2. 关键难点解析

### 2.1 为什么选 RocketMQ 顺序消息而不是"异步但无序"
普通异步（并发消费）下，同一帖子短时间内 LIKE → UNLIKE 两条消息可能被不同消费者
并行处理：UNLIKE 先到、LIKE 后到，DB 终态"已点赞"但实际应该是"未点赞"——**最终
一致性的"最终"错了**。顺序消息保证同一 postId 的互动严格 FIFO，终态 = 最后一次操作的
结果。这是用 MQ 做互动异步化的**必要不充分条件**：没有顺序就谈不上正确。

### 2.2 锁-事务竞态的"根除"（HANDOFF 陷阱 #7 复活）
`PostInteractionService` 在切片 #5 引入分布式锁 + 类级 `@Transactional`，
时序退化成 `unlock` 先于 `commit`：启动稍晚的线程在赢家已解锁、事务未提交时拿到锁，
`existsById` 读不到未提交的行 → 重复 insert → 撞复合主键 → 500。切片 #10 split-08
修过一次（`UserFollowService`），本切片在 `PostInteractionService` **同构根除**：
删类级 `@Transactional`，让 `TransactionTemplate` 在锁内自开自提交（`lock →
redisStore.flip → publish 或降级(projection.apply 内含 tx) → unlock`），
降级路径 commit 先于 unlock，窗口结构性关闭。

### 2.3 @ConditionalOnProperty 三态互斥（测试安全的命门）
三个组件必须三态互斥：
- `RocketMqInteractionEventPublisher`：`@ConditionalOnProperty(async=true)` → 发 MQ。
- `NoopInteractionEventPublisher`：`@ConditionalOnProperty(async=false, matchIfMissing=true)` → 同步降级（缺省）。
- `InteractionStreamConfig`（定义 `Consumer<InteractionEvent>` bean）：
  **同样** `@ConditionalOnProperty(async=true)`。

**关键决策变更**：原计划 InteractionStreamConfig 无条件（避免漏 bean），但发现 Spring
Cloud Stream 会自动探测唯一的 Consumer bean 创建绑定并连 binder 地址。测试 profile 若
也加载这个 bean，就会连 `localhost:9876`（哑 name-server）→ binder 启动失败。
改为**同条件**后，测试 profile（async=false）连 StreamConfig 都不会加载，从根上规避
binder 启动风险。

### 2.4 Lua 回填的原子性防丢失更新
并发场景：10 个线程同时 miss 同一计数 key，若各自 `GET → 判断 miss → SET 基线 → INCR`，
基线会被覆盖 10 次（最后一次的基线覆盖前 9 次），`INCR` 累加但基线只算一次。
用 Lua 脚本把"基线只设一次 + INCRBY"原子化：`if exists==0 then set baseline; expire; end;
return incrby(delta)`。`EVALSHA` 单命令原子执行，10 个线程中只有第一个能设基线，
其余都按"已存在基线 + delta"累计——**计数绝不丢失更新**。

### 2.5 重复点击幂等的双层防护
- **Redis 层**：`flip()` 读 current，若 current==target（已点赞再点赞 / 已取消再取消），
  直接返回当前计数、**不修改计数**——Redis 侧幂等。
- **DB 层**：消费端 `persist()` 用 `existsById` 复合主键防重：
  - LIKE：不存在 → `save(PostLike)` + `incrementLikeCount`；存在 → 跳过。
  - UNLIKE：存在 → `deleteById` + `decrementLikeCount`；不存在 → 跳过。

  同一条事件被消费两次（重试/重发），DB 仅落一行、计数仅 +1。

### 2.6 RocketMQ 容器化测试经典坑：brokerIP1 不可达
RocketMQ 客户端连 broker 的流程：① 连 namesrv 拿路由（包含 brokerAddr）；
② 按路由里的 brokerAddr 连 broker。Testcontainers 默认容器内网 IP 作为 brokerAddr
（例如 172.19.0.3:10911），**宿主机不可达**——客户端拿到的路由指向容器内网，连不上。
解法：在 `broker-test.conf` 里 `brokerIP1=localhost` + `listenPort=10911`，让 broker
注册到 namesrv 的地址是 `localhost:10911`；再用 `withCreateContainerCmdModifier`
把容器 10911/10909 **固定端口绑定到宿主**（`HostConfig.newHostConfig().withPortBindings(...)`），
客户端就连得上了。

### 2.7 测试策略：确定性优先 + gated e2e
主套件（async=false）跑**同步降级路径**，Redis 与 DB 同步对齐——现有精确计数断言
（3700→3701→3701→3700）仍然成立，**无需改为轮询**。异步链路由两个专属测试覆盖：
- `InteractionAsyncIntegrationTest`（**确定性、无 broker**）：`@Primary
  RecordingPublisher` 顶替 Noop，开关切换 deliver=true/false，验证异步投递/降级/
  消费幂等/顺序终态/重复点击幂等/事件契约。
- `InteractionRocketMQIntegrationTest`（**gated**，`@Tag + @EnabledIfSystemProperty
  (kuros.it.rocketmq=true)`）：真实 namesrv+broker 容器，验证 send→顺序消费→DB
  最终一致 + 顺序终态。默认 `mvn test` 不跑（保持主套件确定性），CI 显式触发。

## 3. 简历 STAR 写法（含"原方案为何不行" + 收益量化）

- **S（情境）**：求职作品——仿小红书的高并发内容社区。互动（点赞/收藏）写路径
  是经典同步：锁 → 事务内 `UPDATE posts SET like_count = like_count + 1`
  （行锁）→ 落关系表 → 返回。一个热门帖被多人同时点赞时，多线程争同一行
  `like_count` 的行锁，并发越高争抢越激烈，且请求线程 = 事务线程 = 响应线程，
  任一环慢直接拖慢响应。
- **T（任务）**：把互动写路径从同步改造为"Redis 计数前置 + RocketMQ 顺序消息
  异步落库"，**结构性消除**热点行竞争、兑现 MQ 的"削峰/异步/重试/死信"价值，
  并留下可被面试深挖的决策链。
- **原方案为何不行**：① 继续同步——热点行竞争随并发指数恶化，无缓冲；
  ② Redis-only 不持久化——互动数据丢失、无法按用户查"我赞过的帖子"；
  ③ Redis + MQ 但 MQ 无序——同一帖子 LIKE→UNLIKE 被并行消费，终态错乱
  （DB 显示"已点赞"但实际应该是"未点赞"）；
  ④ Kafka 替代 RocketMQ——顺序消息语义弱于 RocketMQ 分区顺序（Kafka 单分区
  内有序，但跨分区不保证），且 Spring Cloud Stream Kafka 的 partitioner 配置
  不如 RocketMQ 直观。
- **A（行动）**：
  - Redis 计数前置（状态/计数两类 key + 两段 Lua 原子回填 + 幂等 flip）；
  - RocketMQ 顺序消息（按 postId 分区 orderly=true，消费端顺序消费）；
  - 消费端事务内幂等落库（复合主键 + existsById 防重 + TransactionTemplate
    自开自提交）+ 刷 DB 冗余计数列；
  - 发送失败/未启用 → 同步降级落库（复用消费端同一段落库逻辑）；
  - 测试分确定性（无 broker RecordingPublisher）+ gated e2e（真实 broker）两层；
  - 锁-事务竞态根除（删类级 @Transactional，降级路径 commit 先于 unlock）。
- **R（结果，可验证的结构性收益）**：
  - **热点行竞争结构性消除**：同一帖子的所有互动经同一分区顺序消费，DB 层面
    单线程串行处理，不再有跨用户行锁竞争。
  - **请求即时反馈**：写路径只写 Redis（状态 key + 计数）即返回，不再等 DB 事务。
  - **互动不丢**：MQ 发送失败降级为同步落库；MQ 发送成功由 broker 持久化 + 重试 +
    死信兜底。
  - **重复点击幂等**：Redis 侧（current==target 不修改计数）+ DB 侧（existsById
    防重）双层防护，重复点赞/取消不重复计数。
  - **测试策略完整**：主套件（async=false 同步降级）保持确定性 + 专属测试覆盖
    异步链路（投递/降级/幂等/顺序终态）+ gated e2e 真实 broker 验证。
  - **编译级验证**：main compile 96 文件 BUILD SUCCESS（零新增 warning）+
    test-compile BUILD SUCCESS + compose config 11 服务解析正常。
  - （性能类收益如"QPS 提升"留压测切片以数据佐证，不做无依据表述。）

## 4. 原理详解

### 4.1 写路径全链路
```
浏览器 ── POST /api/v1/posts/{postId}/like ──▶ Gateway ──▶ kuros-backend

PostInteractionController.like(postId, userId)
   ▼
PostInteractionService.interact(postId, userId, LIKE, "like")
   │
   ├─ lockKey = "lock:like:{postId}:{userId}"（per-user 锁，TTL 3s）
   │  distributedLock.tryLock → 失败则返回当前快照（不阻塞）
   │
   ├─ findPublishedPost（DB 查帖子，校验 PUBLISHED）
   │
   ├─ redisStore.flip(LIKE, postId, userId, dbExists, dbBaseline)
   │     readState: GET stateKey → miss → STATE_BACKFILL Lua（原子回填）
   │     若 current==target（已点赞再点赞）→ 直接返回 readCount（幂等）
   │     否则：set stateKey "1" + COUNT_BACKFILL_DELTA Lua（原子 +1）
   │     返回新计数
   │
   ├─ InteractionEvent.of(postId, userId, LIKE)
   │  eventPublisher.publish(event)
   │     ├─ RocketMq 成功 → 返回 true（异步落库由消费端处理）
   │     └─ 失败 → 返回 false → projectionService.apply(event) 同步落库
   │           TransactionTemplate.executeWithoutResult → persist(event)
   │             switch LIKE: existsById否 → save(PostLike) + incrementLikeCount
   │             @CacheEvict postDetail
   │
   ├─ findPost(postId, userId) 返回实时快照（Redis-first）
   │
   └─ finally: distributedLock.unlock

RocketMQ 消费端（顺序消费，单线程）：
   interactionConsumer(InteractionEvent event)
     └─ projectionService.apply(event)  // 同上落库逻辑
```

### 4.2 Lua 回填脚本语义
- **COUNT_BACKFILL_DELTA**：`if exists(KEYS[1])==0 then set(KEYS[1],ARGV[1]);
  expire(KEYS[1],ARGV[3]); end; return incrby(KEYS[1],ARGV[2])`
  - 用途：计数 key miss 时，用 DB 基线初始化 + delta（±1）原子累计。
  - ARGV[1]=DB 基线、ARGV[2]=delta、ARGV[3]=TTL。
  - 并发 miss 时只有第一个能 set 基线，其余都按"已存在基线 + delta"累计。
- **STATE_BACKFILL**：`if exists(KEYS[1])==0 then set(KEYS[1],ARGV[1]);
  expire(KEYS[1],ARGV[2]); end; return get(KEYS[1])`
  - 用途：状态 key miss 时，用 DB exists 结果初始化，返回最终状态。
  - ARGV[1]=DB 状态（"1"/"0"）、ARGV[2]=TTL。

### 4.3 @ConditionalOnProperty 三态互斥表
| 属性值 | RocketMqPublisher | NoopPublisher | InteractionStreamConfig | 行为 |
|---|---|---|---|---|
| async=true（生产） | ✅ 加载 | ❌ | ✅ 加载 | 发 MQ + 消费端处理 |
| async=false（测试/降级） | ❌ | ✅ 加载 | ❌ | 同步降级落库 |
| 未配置（缺省） | ❌ | ✅ 加载（matchIfMissing） | ❌ | 同步降级（保守默认） |

### 4.4 降级路径时序（关键：commit 先于 unlock）
```
降级路径（eventPublisher.publish 返回 false）：
   lock → ... → projectionService.apply(event)
                 └─ @CacheEvict 先于方法体执行（AOP 顺序）
                 └─ transactionTemplate.executeWithoutResult
                      └─ persist(event)  // DB 写
                      └─ tx commit         // ← 事务在此提交
                 └─ apply 返回
   ... finally: unlock                       // ← 解锁在 commit 之后
```
窗口结构性关闭：其他线程拿到锁时，DB 已 commit 可见。

## 5. 面试追问链回答清单（≥5 条）

**Q1：为什么选 RocketMQ 不选 Kafka？**
A：顺序消息语义 + 国内生态。RocketMQ 分区顺序消息（`orderly=true`）是原生一等公民，
Spring Cloud Stream 配置直观（`rocketmq.consumer.orderly=true`）。Kafka 单分区内有序，
但 Spring Cloud Stream Kafka 的 partitioner 默认用 hash(payload)，要显式配
`partition-key-expression=payload.postId` 才能保证同帖子消息落同分区；且 Kafka 顺序
消费需要 `max.poll.records=1` + 单 partition + 单 consumer，配置更繁琐。本项目参照
《小哈书》教程用 RocketMQ，面试叙事与教程对齐更顺畅。

**Q2：为什么顺序消息是"必要不充分条件"？**
A：必要——没有顺序，LIKE→UNLIKE 可能被并行消费成 UNLIKE→LIKE，终态错乱。不充分——
有了顺序，还要配合**幂等落库**（existsById 防重 + 复合主键兜底）+ **事务内刷计数**
（保证关系行与计数列同事务）才能正确。顺序只解决了"消费顺序"，没解决"重试幂等"
和"事务边界"。

**Q3：为什么 Redis 计数要 Lua 原子回填，而不是 GET→SET→INCR？**
A：并发 miss 场景。10 个线程同时 miss 同一计数 key：
- GET→SET→INCR：每个线程都 SET 基线（覆盖前一个的基线），最后 INCR 累加但基线只算
  一次——**计数丢失更新**（假设 DB 基线 100，10 个线程各 +1，期望 110，实际 101）。
- Lua 原子回填：`if exists==0 then set baseline` 只有第一个能 set，其余都按"已存在
  基线 + delta"累计——10 个线程后计数 = 100 + 10 = 110，正确。

**Q4：为什么同步降级不另写一段逻辑，而是复用消费端的 `projection.apply`？**
A：DRY + 防分叉。降级路径与消费路径的业务逻辑必须严格一致（都写关系表 + 刷计数列 +
evict 缓存）。如果各写一段，未来改一处忘改另一处 → 数据不一致。让降级路径复用消费端
同一段逻辑，"降级路径 = 消费路径跳过 MQ 直跑"，行为一致性由代码共享保证。

**Q5：测试为什么不用 Awaitility 轮询，而用手写 awaitUntil？**
A：依赖洁癖 + 显式控制。Awaitility 是标准选择，但本项目未引入（pom 不加无谓依赖）。
手写 `awaitUntil(BooleanSupplier, Duration)` 15 行代码搞定：`while (!supplier.getAsBoolean()
&& elapsed < timeout) { Thread.sleep(100); }`。面试可讲：轮询间隔（100ms）× 超时（10s）
= 最多 100 次检查；退避策略可加指数退避防 CPU 空转；生产级用 Awaitility 的
`atMost().pollInterval().until()` 更规范。

**Q6（加分）：brokerIP1 不可达是什么坑？怎么定位的？**
A：RocketMQ 客户端连 broker 的两段式：① 连 namesrv 拿路由（含 brokerAddr）；
② 按 brokerAddr 连 broker。Testcontainers 容器内网 IP（如 172.19.0.3）被 broker
注册到 namesrv，宿主客户端拿到的路由指向容器内网 → 连不上。症状：broker 启动日志
正常，但客户端 connect 超时。定位：在容器内 `cat /home/rocketmq/store/config/brokerAddr`
看到容器内网 IP；修复：broker.conf 写 `brokerIP1=localhost` + `listenPort=10911`，
让 broker 注册 `localhost:10911` 到 namesrv，再用 `withCreateContainerCmdModifier`
把容器 10911 固定绑定到宿主 10911。

**Q7（加分）：InteractionStreamConfig 为什么不能无条件加载？**
A：Spring Cloud Stream 自动探测唯一 Consumer bean 创建绑定并连 binder 地址。测试
profile（async=false）若也加载这个 bean，SCS 会尝试连 `localhost:9876`（哑 name-server）
→ binder 启动失败/超时。改为 `@ConditionalOnProperty(async=true)` 后，测试 profile
连 StreamConfig 都不会加载，从根上规避 binder 启动风险。这是"条件化三态"的命门——
不只是 producer 条件化，**consumer 配置也要条件化**。

## 6. 技术选型对比

### 6.1 互动异步化方案对比

| 维度 | Redis-only（无持久化） | DB-only（同步，原方案） | Redis + MQ + DB 异步（本项目） | Redis + Kafka + DB |
|---|---|---|---|---|
| 持久化 | ❌ 进程重启丢失 | ✅ 立即 | ✅ MQ 持久化 + 重试 | ✅ Kafka 持久化 |
| 热点行竞争 | 无 DB 写无竞争 | 行锁互斥 | 队列串行化消除 | 同左（配 partitioner） |
| 即时反馈 | ✅ 写 Redis 即返回 | ❌ 等 DB 事务 | ✅ 写 Redis 即返回 | 同左 |
| 顺序保证 | N/A | N/A | ✅ 分区顺序消息 orderly=true | ⚠️ 单分区内有序（配复杂） |
| 重试/死信 | 无 | 无 | ✅ 自动重试 + 死信队列 | ✅ 重试（配复杂） |
| 配置复杂度 | 低 | 低 | 中（binder + binding + 顺序配置） | 高（partitioner + 单分区约束） |
| 适用边界 | 允许丢失的轻互动（浏览计数） | 低并发 / 强一致要求 | **高并发 + 不允许丢失 + 国内生态**（本项目场景） | 已有 Kafka 基建 / 海外项目 |

### 6.2 关键决策的备选方案（ADR 0004）

| 决策点 | 选定 | 拒绝的备选 | 拒绝理由 |
|---|---|---|---|
| MQ 选型 | RocketMQ（SCA 生态） | Kafka | 顺序消息配置更直观、国内生态对齐《小哈书》 |
| 消息语义 | 分区顺序消息（orderly=true） | 普通消息 | 无序 → LIKE→UNLIKE 终态错乱 |
| 计数回填 | Lua 原子回填 | GET→SET→INCR | 并发 miss 丢失更新 |
| 降级策略 | 同步复用消费端落库 | 单独写降级逻辑 | DRY + 防分叉（行为一致性由代码共享保证） |
| 测试策略 | 确定性（RecordingPublisher） + gated e2e | 全 mock / 全真实 broker | 确定性优先 + e2e 交 CI |
| 锁-事务 | TransactionTemplate（锁内 commit） | 类级 @Transactional | 类级注解让 unlock 先于 commit，竞态窗口 |

## 7. 面试叙事模板

**30 秒电梯版**：我把一个内容社区的点赞/收藏写路径从同步改造为异步。做法是请求线程
只写 Redis（状态 key + 计数）即时返回，发互动事件到 RocketMQ 按帖子 ID 分区顺序消息，
消费端单线程顺序消费、事务内幂等落库——把跨用户对热点帖计数行的 DB 行锁竞争串行化到
单队列单线程根除。MQ 发送失败同步降级落库（复用消费端同一段逻辑），互动绝不丢。
测试分确定性（无 broker RecordingPublisher）+ gated e2e（真实 broker）两层。

**2 分钟详细版**：在 30 秒版基础上补充——
① **为什么此刻改**：前置积木就绪——分布式会话（#1）+ 分布式锁（#5）+ 服务拆分（#10）
已把"锁-事务竞态"这个坑踩过一次（split-08 修 `UserFollowService`），本切片在
`PostInteractionService` 同构根除，面试可讲"两次踩同一坑的认知深化"；
② **为什么选 RocketMQ 不选 Kafka**：顺序消息是一等公民（orderly=true 配置直观），
Kafka 单分区内有序但配置繁琐（partitioner + max.poll.records=1）；
③ **为什么顺序是必要不充分**：必要——无序则 LIKE→UNLIKE 终态错乱；不充分——
还要幂等落库 + 事务内刷计数；
④ **Lua 回填防丢失更新**：并发 miss 时只有第一个 set 基线，其余按"已存在基线 + delta"
累计，计数绝不丢失；
⑤ **降级路径复用消费端**：DRY + 防分叉，"降级路径 = 消费路径跳过 MQ 直跑"；
⑥ **真实踩坑**：brokerIP1 不可达（客户端拿到的路由指向容器内网，连不上）+ 
@ConditionalOnProperty 三态互斥（consumer 配置也要条件化，否则测试连 broker）+
ValueOperations.set 废弃 API（Spring Data Redis 4 改用 Duration 重载）；
⑦ **收益验证**：热点行竞争结构性消除（队列串行化）、请求即时反馈（写 Redis 即返回）、
互动不丢（降级兜底）、重复点击幂等（Redis + DB 双层防护），全部可编译验证，
性能收益留压测切片用数据说话。
