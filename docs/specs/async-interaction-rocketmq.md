# Spec: 切片 #11 —— 互动写路径异步化（Redis 计数前置 + RocketMQ 顺序消息落库）

> 关联：ADR `docs/adr/0004-async-interaction-rocketmq.md`；术语见 `CONTEXT.md`（互动 / 热点内容 / 实时计数）。
> 遵循 CONTEXT.md L169「切片定义模式（强制）」四段式：① 难点叙事 → ② 方案选型 → ③ 功能载体 → ④ 验证与叙事。

---

## ① 难点叙事（Problem Statement）

### 具体问题

点赞 / 收藏是内容社区**频率最高、最典型的写操作**。现状为**同步写路径**：HTTP 请求线程内完成「Redis 分布式锁 → 锁内事务 → `existsById` 防重 → `save` 关系行 → `UPDATE posts SET like_count = like_count + 1 WHERE id = ?` 原子刷计数 → `@CacheEvict postDetail` → 返回实时计数」。三道防线（锁 + `existsById` + 复合主键）已保证幂等（见 `PostInteractionService`）。

但这条路径埋着三个**结构性矛盾**（本项目无生产流量，以下为结构性论证，非事故复盘，不编造并发数字）：

1. **热点内容计数行是跨用户 DB 写竞争点**：现有 Redis 锁的 key 是 `lock:like:{postId}:{userId}`——它只串行化「同一用户对同一帖」的重复点击，**不跨用户互斥**。当不同用户点赞同一热点内容时，它们各自的 `UPDATE posts ... WHERE id = :postId` 会在**同一行**上争 InnoDB 行锁，热帖越热、并发点赞越多，行锁排队越严重。Redis 锁对此**无能为力**，因为竞争发生在 DB 层、跨用户。
2. **写路径职责耦合**：持久化关系 + 更新计数 + 驱逐缓存全部耦合在一次同步请求内，任一环节（尤其 DB 抖动 / 慢查询）直接拖垮点赞可用性，无缓冲、无削峰。
3. **无削峰能力**：热点内容被推荐位引爆时，突发写流量 1:1 直接砸 DB，没有异步缓冲层。

### 原方案为何不行

「加更粗的 Redis 锁（按 postId 全局锁）」能消除 DB 行锁竞争，但把并发写**串行化到锁上**——热帖点赞退化为全局排队，吞吐随热度崩塌，且锁粒度越粗、锁失败返回快照的概率越高（用户点了没反应）。「DB 层换更轻的更新」治标不治本：只要请求线程**同步 UPDATE 同一计数行**，行锁竞争就存在。真正的解法是**让请求线程不再同步写 DB 计数行**，把对同一帖的计数变更**收敛到单条有序队列串行落库**——竞争从「多请求线程争 DB 行锁」变为「单消费线程顺序写」，根除而非缓解。

### 规模假设

以「一个热点内容在短时间内被大量不同用户并发点赞 / 收藏」为演示场景；收益以**可验证的结构性指标**佐证（写路径不再同步 `UPDATE posts`、同一帖计数变更串行到单队列、MQ 不可用时同步降级不丢），**不做无压测依据的 TPS/RT 数字表述**（性能对比留可选压测，交用户执行）。

---

## ② 方案选型（≥2 候选，选 / 拒理由）

### 主方案对比

| 方案 | 即时反馈 | 根除热点行竞争 | 可靠（重试/死信/削峰） | 契合技术栈锁定 | 结论 |
|---|---|---|---|---|---|
| **A. Redis 计数前置 + RocketMQ 顺序消息落库（按 postId 分区）** | ✅ Redis 实时 | ✅ 同帖串行单队列 | ✅ 原生重试+死信 | ✅ RocketMQ 为异步主线 | **选定** |
| B. 纯 MQ 落库（无 Redis 前置） | ❌ 点赞后刷新看不到即时状态/计数 | ✅ | ✅ | ✅ | 拒：体验倒退 |
| C. RocketMQ 普通并发消息（非顺序） | ✅ | ❌ 竞争只是从请求线程转移到消费线程 | ✅ | ✅ | 拒：没根除 |
| D. Redis 前置 + 定时批量落库 / Redis Stream | ✅ | ✅ | ⚠️ 丢事件粒度、难做重试/死信 | ❌ 违反 RocketMQ 主线锁定 | 拒：仅进对比不实现 |
| E. RocketMQ 事务消息（half message） | ✅ | ✅ | ✅ | ✅ | 拒：本方案关系落库在消费端、请求线程无本地 DB 事务，事务消息过度设计 |
| F. `@Async` 线程池异步替代 MQ | ✅ | ✅ | ❌ 重启丢任务、无重试/死信、不跨实例削峰 | ❌ | 拒：进程内异步不是「异步主线」正解 |

**选 A 的核心理由**：只有「Redis 前置」能同时满足即时反馈 + 请求线程脱离 DB 写；只有「按 postId 顺序消息」能把同一热点内容的计数变更串行到单队列单线程**根除**跨用户 DB 行锁竞争（不同帖仍并行、不降吞吐）；RocketMQ 原生重试 / 死信 / 削峰兑现「可靠异步」叙事，且与 CONTEXT.md L171 技术栈锁定一致。

### 客户端选型

| 候选 | 结论 | 理由 |
|---|---|---|
| **`spring-cloud-starter-stream-rocketmq`（`com.alibaba.cloud`，SCA 2025.1.0.0）** | **选定** | 与现有 SCA 栈（Nacos discovery/config）版本对齐；已适配 Spring Boot 4.0 / Spring 7；Spring Cloud Stream function 模型（`StreamBridge` 发送 + `Consumer<T>` 消费），binder 屏蔽底层 client 细节 |
| `rocketmq-spring-boot-starter` 2.3.x | 拒 | 面向 Boot 3.x / Spring 6，对 Spring 7 无官方适配保证，版本风险高 |

> 实际捆绑的 rocketmq-client 版本以 `mvn dependency:tree` 为准；若触发 Spring Cloud 兼容校验失败，沿用项目先例 `spring.cloud.compatibility-verifier.enabled=false`。

### 顺序消息粒度

- **按 postId hash 选队列（选定）**：同一帖计数变更串行、不同帖并行，兼顾「根除竞争」与「吞吐」。
- 全局顺序（单队列）：拒——牺牲全部并行度。
- 普通并发：拒——见方案 C。

---

## ③ 功能载体（Solution：只做演示难点所需）

> 原则：只实现「能完整演示并验证『热点行竞争根除 + 可靠异步落库』」的功能，不做与之无关的功能。

### 3.1 Redis 实时读源数据模型

| 用途 | Key | 类型 | 操作 | 回填（miss 时） |
|---|---|---|---|---|
| 点赞状态 | `interaction:like:{postId}:{userId}` | String `"1"`/`"0"` | 写：SET；读：GET | 从 DB `post_like.existsById` 回填 `"1"`/`"0"` + TTL |
| 收藏状态 | `interaction:favorite:{postId}:{userId}` | String `"1"`/`"0"` | 同上 | 从 DB `post_favorite.existsById` 回填 + TTL |
| 点赞计数 | `interaction:count:like:{postId}` | String(整数) | INCR / DECR | 从 DB `posts.like_count` SET 基线 + TTL |
| 收藏计数 | `interaction:count:favorite:{postId}` | String(整数) | INCR / DECR | 从 DB `posts.favorite_count` SET 基线 + TTL |

- **为何用「单 key 状态」而非「全量 Set」**：本切片无「列出谁赞了」需求；单 key 回填**幂等无竞态**（只影响自身），避免热帖全量 Set 的内存与回填成本。
- **计数回填竞态**：用 Lua 原子化「若 key 不存在则以 DB 基线 SET，再 INCRBY」——基线由应用层先从 DB 读入并作为参数传入，Lua 内 `EXISTS` 判断保证基线只被设一次，后续叠加 INCR，杜绝并发 miss 重复回填。
- **状态与计数的一致性**：写路径在 per-user 锁内先判旧状态，仅当状态发生翻转（未赞→赞 / 赞→未赞）时才 INCR/DECR，保证重复点击不重复计数。

### 3.2 写路径改造（`PostInteractionService.like/favorite/unlike/unfavorite`）

1. 抢 per-user 分布式锁 `lock:{type}:{postId}:{userId}`（复用现有 `DistributedLock`，TTL 3s）；抢锁失败 → 返回当前 Redis 快照（幂等，与现状一致）。
2. 锁内（**不再同步 `UPDATE posts`**）：
   a. 校验帖 `PUBLISHED`（读，走 `postDetail` 缓存 / DB，读不争写行锁）。
   b. **回填保障**：若计数 key / 状态 key miss，则从 DB 回填基线（见 3.1）。仅 Redis 冷时触发；热路径命中不读 DB。
   c. 翻转判定 + SET 新状态 + 条件 INCR/DECR 计数。
   d. **发 MQ 事件**到 `post-interaction-topic`，顺序消息、分区键 = `postId`。
   e. **发送失败 → 同步降级**：锁内 `TransactionTemplate` 执行原同步写路径（`existsById` + `save`/`delete` 关系行 + `increment/decrementCount`），保证互动不丢；Redis 已前置，降级只补 DB。
   f. 返回 `PostInteractionResponse`（值取自 **Redis 实时读源**）。
3. 释放锁。

> `PostInteractionResponse` 契约不变（`postId, likeCount, favoriteCount, liked, favorited`）；语义从「DB 精确值」变为「Redis 实时值，最终一致于 DB」。

### 3.3 互动事件模型

```
InteractionEvent {
  String eventId;        // UUID，追踪 / 幂等日志
  String postId;         // 分区键（顺序消息）
  String userId;
  InteractionType type;  // LIKE | UNLIKE | FAVORITE | UNFAVORITE
  long occurredAt;       // epoch milli
}
```

### 3.4 消费端（顺序消费，幂等落库）

- `Consumer<InteractionEvent>`（Spring Cloud Stream function），binding destination = `post-interaction-topic`，**顺序消费**（同一 postId 落同一队列、单线程串行）。
- 事务内幂等落库：
  - LIKE → `if (!existsById) { save(PostLike); incrementLikeCount(postId); }`
  - UNLIKE → `if (existsById) { deleteById; decrementLikeCount(postId); }`
  - FAVORITE / UNFAVORITE 同理（`favorite` 计数列）。
- **幂等保证**：`existsById` + 复合主键 → 重复消费 / 重投不会重复计数（LIKE 时若关系行已存在则跳过 increment）。
- **重试 + 死信**：Spring Cloud Stream `max-attempts` + RocketMQ 原生重试；多次失败进死信队列 `%DLQ%{consumerGroup}`。

### 3.5 读路径（互动快照）

- `GET /api/v1/posts/{postId}/interactions`（`findPost`）：`liked`/`favorited` 与 `likeCount`/`favoriteCount` 走 **Redis 优先**，miss 回源 DB 冗余列 / `existsById` 并回填。
- **范围收敛**：帖子**详情 / 列表**的计数展示本切片保持读 DB 冗余列（消费端已刷，最终一致）；其 Redis 优先属读路径加固，留切片 #13。

### 3.6 基础设施

- **依赖**：`kuros-backend/pom.xml` 加 `spring-cloud-starter-stream-rocketmq`（SCA BOM 管版本）。
- **配置**（`application.properties`）：
  - `spring.cloud.stream.rocketmq.binder.name-server=<namesrv>:9876`
  - `spring.cloud.stream.bindings.<consumerBinding>.destination=post-interaction-topic` / `.group=interaction-consumer-group`
  - `spring.cloud.function.definition=<consumerFunction>`
  - 顺序消息：producer 端 orderly + 分区键 postId（精确配置以 binder 文档为准）
  - `spring.cloud.stream.bindings.<consumerBinding>.consumer.max-attempts`
- **compose**（8 → 11 服务）：
  - `rocketmq-namesrv`：`apache/rocketmq:5.3.x`，`sh mqnamesrv`，9876。
  - `rocketmq-broker`：`apache/rocketmq:5.3.x`，`sh mqbroker -n rocketmq-namesrv:9876`，配 `brokerIP1` + `autoCreateTopicEnable=true`，端口 10909/10911/10912。
  - `rocketmq-dashboard`：`apacherocketmq/rocketmq-dashboard`，宿主 **8082**（避开 gateway 8080 / nacos 8081），env `JAVA_OPTS=-Drocketmq.namesrv.addr=rocketmq-namesrv:9876`。
  - Docker Hub 受限时优先 quay.io 镜像源。

---

## ④ 验证与叙事（Testing Decisions）

### 4.1 测试接缝（优先复用现有、取最高接缝）

| 接缝 | 位置 | 用途 | 新增/复用 |
|---|---|---|---|
| MockMvc HTTP | `POST/DELETE /api/v1/posts/{id}/interactions/{like,favorite}` | 端到端断言即时反馈（Redis 实时值） | **复用**（`KurosBackendApplicationTests`） |
| 最终一致轮询 | 测试辅助 await/poll DB | 异步落库后断言 DB 计数 / 关系收敛 | 新增（辅助工具，优先复用现有栈，必要时引入 Awaitility） |
| 消费端单元 | 直接调用 `Consumer<InteractionEvent>` | 幂等落库 / 计数正确性（绕过 MQ 传输） | 新增 |
| 降级路径 | mock/spy `StreamBridge` 发送抛异常 | 断言同步降级落库不丢 | 新增（注入点） |
| RocketMQ Testcontainers | 真实 namesrv+broker 容器 | 端到端「点赞→发消息→顺序消费→DB 最终一致」 | 新增（**耗时，交用户/CI 执行**） |

### 4.2 测试改造与新增

- **改造现有**：`KurosBackendApplicationTests` 中「同步断言精确计数（如 likeCount 3700→3701、favorite 1200→1201）」在异步化后失效 → 改为**最终一致断言**（轮询 DB 直至收敛或超时）；即时反馈断言改为校验 HTTP 响应体中的 Redis 实时值。
- **新增用例**：
  1. 点赞后 HTTP 响应即时 `liked=true` 且 `likeCount` = 基线+1（Redis 实时）。
  2. 重复点赞幂等：多次 POST → Redis 计数只 +1、DB 关系一行、消费不重复计数。
  3. 取消点赞 / 收藏 / 取消收藏对称。
  4. 消费幂等：同一 `InteractionEvent` 消费两次 → DB 关系一行 + 计数只 +1。
  5. 降级：`StreamBridge` 发送失败 → DB 关系 + 计数同步落库、不丢、响应正常。
  6. 顺序性：同一 postId 的 LIKE→UNLIKE 序列消费后终态正确（DB 关系不存在、计数回到基线）。
  7. 游客 GET 快照：未登录返回计数、`liked/favorited=false`。
- **测试隔离**：沿用 `TestDatabases`（唯一 H2 库名工厂）+ Surefire `reuseForks=false` 每类独立 JVM + `@DirtiesContext` + Testcontainers Redis；RocketMQ 容器测试单独类，避免与 H2 上下文串味。

### 4.3 叙事产出（切片 DoD 增量，CONTEXT.md L168）

- **STAR 面试故事** + **≥5 条追问链回答清单**（如：为何顺序消息而非并发？Redis 与 DB 计数漂移如何对账？降级如何保证不丢？幂等如何保证？为何不用事务消息？热帖 Set 内存如何治理？）
- **方案对比表**（含适用边界）——即本 spec ② 节。
- **真实数据**：结构性验证证据（写路径无同步 UPDATE、降级路径覆盖、顺序消费串行化）；性能类数据留可选压测（交用户跑），无数据则只做结构性论证。
- **七段式复盘** → `docs/learning/11-*.md`。

---

## Out of Scope（不做的事）

1. **不修复 `commentCount` / `viewCount` 失联**（有列却从不维护的真实缺陷）：属计数一致性范畴，纳入会稀释「高并发互动写」主叙事；显式排除，记录为已知缺陷留独立工单 / #13。
2. **不改帖子详情 / 列表计数的读源**（保持读 DB 冗余列）：Redis 优先读属读路径加固，留 #13。
3. **不引入 Redis↔DB 计数对账 / 校准任务**：留 #13。
4. **评论内容写入保持同步**（用户期望立即看到自己的评论），不异步化。
5. **不做关注计数异步化**（关注数在 kuros-user 是纯实时 COUNT，无冗余列，不属本切片）。
6. **不引入 RocketMQ 事务消息 / 消息轨迹平台**：过度设计。
7. **不做多 broker / 主从集群**：单 namesrv + 单 broker 足够演示。

## Further Notes

- **一致性模型变更**：互动计数从「同步强一致」变为「Redis 实时 + DB 最终一致」，引入短暂滞后窗口（顺序消费通常亚秒级）；`PostInteractionResponse` 契约字段不变、语义变更，前端零改动。
- **已知限制**：Redis 与 DB 计数可能短暂不一致（对账留 #13）；Redis 挂时降级读 DB（滞后不崩）；镜像拉取受限时用户环境需加速 / 私仓。
- **收益验证方式（可复现）**：① 写路径不再同步 `UPDATE posts`（DB 写压力削峰）；② 热点行竞争消除（按 postId 顺序消费串行化）；③ MQ 不可用时同步降级保证不丢。
- **对应小哈书章节**：RocketMQ 异步削峰、顺序消息、幂等消费、死信重试；高并发点赞 / 计数（Redis + MQ）。
