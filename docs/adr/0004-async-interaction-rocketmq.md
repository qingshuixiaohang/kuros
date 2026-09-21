# 0004. 互动写路径异步化：Redis 计数前置 + RocketMQ 顺序消息落库

日期：2026-09-21

状态：已接受

## 背景与问题

点赞/收藏现为**同步写路径**：HTTP 请求线程内 → Redis 分布式锁（`lock:like:{postId}:{userId}`，TTL 3s）→ 锁内 `TransactionTemplate` 事务 → `existsById` 防重 → save 关系行 + 原子 `UPDATE posts` 冗余计数列（`CASE WHEN >0` 防负）→ `@CacheEvict postDetail` → 返回实时计数。三道防线（锁 + `existsById` + 复合主键）保证幂等。

暴露三个结构性矛盾（本项目无生产流量，为结构性论证，非事故复盘）：

1. **热点内容计数行是跨用户 DB 写竞争点**：现有 Redis 锁只串行化"同一用户对同一帖"的并发；不同用户点赞同一热帖时，`UPDATE posts SET like_count = like_count + 1 WHERE id = ?` 仍在同一行上争 DB 行锁，热帖越热竞争越剧烈，锁无法缓解。
2. **写路径职责耦合**：持久化关系 + 更新计数 + 驱逐缓存耦合在一次同步请求内，DB 抖动直接拖垮点赞可用性，无缓冲。
3. **无削峰**：热帖被推荐引爆时突发写流量直接砸 DB，无异步缓冲。

**为什么此刻做**：技术栈已锁定 RocketMQ 为异步主线；#10 服务拆分后内容域边界清晰，互动写路径是内容域内最典型的高并发写场景，是兑现"高并发内容社区"面试叙事的核心载体。

## 决策

把点赞/收藏写路径改造为 **Redis 计数前置 + RocketMQ 顺序消息异步落库**：

1. **Redis 计数前置（实时读源）**：互动操作先在 Redis 更新"关系 Set + 计数"，请求线程立即返回 Redis 实时值（即时反馈），不再同步等待 DB。
2. **RocketMQ 顺序消息异步落库**：操作发送互动事件到 `post-interaction-topic`，**按 postId hash 选队列（顺序消息）**；消费者顺序消费，把关系幂等落 DB（复合主键 + `existsById`）+ 刷 DB 冗余计数列。同一帖的计数变更串行到单队列单线程 → **从根上消除跨用户 DB 行锁竞争**；不同帖并行不降吞吐。
3. **读路径 Redis 优先**：liked/favorited 状态与 likeCount/favoriteCount 从 Redis 读，miss 回源 DB 冗余列并回填；DB 计数列退居"持久化后备 + 回源"角色，允许短暂滞后（最终一致）。
4. **MQ 发送失败同步降级**：消息发送失败时 fallback 到原同步写路径（锁 + 事务 + `existsById` + `UPDATE`），保证互动不丢；Redis 计数已前置，降级只补 DB 关系 + 计数。体现"异步优先 + 同步兜底"。
5. **消费重试 + 死信**：Spring Cloud Stream `max-attempts` 重试 + RocketMQ 原生重试 + 死信队列（`%DLQ%`）；幂等消费保证重试安全。
6. **客户端选型**：SCA 2025.1.0.0 的 `spring-cloud-starter-stream-rocketmq`（Spring Cloud Stream function 模型 + StreamBridge），与现有 SCA 栈（Nacos）版本对齐、已适配 Boot 4.0。
7. **范围**：仅点赞/收藏。评论内容写入保持同步（用户期望立即看到自己的评论）；浏览计数、关注计数不在本切片。

## 备选方案

- **纯 MQ 落库（无 Redis 前置）**：Controller 发消息即返回，计数只落 DB。缺点：用户点赞后刷新看不到即时反馈（liked 状态 + 计数都要等消费），体验倒退。
- **普通并发消息（非顺序）**：实现简单，但同一帖计数变更并发消费，DB 行锁竞争只是从"请求线程"转移到"消费线程"，没根除；顺序消息按 postId 分区才真正串行化。
- **Redis 前置 + 定时批量落库（无 MQ）/ Redis Stream**：违反技术栈锁定（RocketMQ 为异步主线），且定时批量丢失事件粒度、难做重试/死信。仅进方案对比不实现。
- **事务消息（half message）**：解决"本地 DB 事务 + 发消息"原子性；但本方案关系落库在消费端、请求线程无本地 DB 事务，事务消息过度设计，发送失败用同步降级兜底即可。
- **线程池异步（`@Async`）替代 MQ**：进程内异步，重启丢任务、无重试/死信、无法跨实例削峰，不是"异步主线"的正解。
- **顺带修复 commentCount 失联**（有列却从不维护的真实缺陷）：属计数一致性范畴，纳入会稀释"高并发互动写"主叙事；显式排除，记录为已知缺陷留独立工单/#13。

## 影响

- **前端**：零改动（API 路径/契约不变；`PostInteractionResponse` 仍返回 likeCount/favoriteCount/liked/favorited，语义从"DB 精确值"变为"Redis 实时值，最终一致于 DB"）。
- **一致性模型**：互动计数从"同步强一致"变为"Redis 实时 + DB 最终一致"，引入短暂滞后窗口（顺序消费通常亚秒级）。
- **部署**：compose 新增 namesrv + broker + dashboard（3 个服务，共 11 服务）；broker 配 `brokerIP1` + `autoCreateTopicEnable`。
- **测试**：现有"同步断言精确计数（3700→3701）"失效，改为最终一致断言（轮询/await）+ RocketMQ Testcontainers 端到端 + 降级路径测试。
- **运维面**：新增 RocketMQ 中间件（监控/死信巡检）；Redis 承载互动实时读源（内存 + key 治理）。
- **已知限制**：Redis 与 DB 计数可能短暂不一致（对账/校准留 #13）；Redis 挂时降级读 DB（滞后不崩）；镜像拉取受限时用户环境需加速/私仓。
- **收益验证方式（可复现）**：① 写路径不再同步 `UPDATE posts`（DB 写压力削峰）；② 热点行竞争消除（按 postId 顺序消费串行化）；③ MQ 不可用时同步降级保证不丢。性能类收益（TPS/RT 对比）留可选压测（交用户跑），无数据则只做结构性论证，不编造并发数字。

## 对应小哈书章节

- RocketMQ 异步削峰、顺序消息、幂等消费、死信重试；高并发点赞/计数场景（Redis + MQ）。

## 简历产出

> 将高并发点赞/收藏写路径从"同步写 MySQL"重构为"Redis 计数前置 + RocketMQ 顺序消息异步落库"：请求线程只写 Redis 即时返回（消除用户等待），互动事件按 postId 分区顺序消费，把跨用户对热点帖计数行的 DB 行锁竞争串行化到单队列根除；幂等消费（复合主键 + existsById）+ 消费重试/死信保证可靠；MQ 发送失败同步降级落库保证不丢。计数读走 Redis 优先回源 DB，前端零改动、契约不变，计数转最终一致。
