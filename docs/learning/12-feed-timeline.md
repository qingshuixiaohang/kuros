# 切片 #12：Feed 流——Redis ZSet Timeline + Push-on-publish

> 本切片实现首页三种信息流（推荐/最新/关注）。关注流基于 Redis ZSet Timeline +
> Push-on-publish 模型：发帖时把 postId 推到粉丝的 timeline ZSet，读时 ZREVRANGE
> 按时间倒序取 postId 分页。推荐/最新流复用现有 DB 查询路径。共 6 个工单
> （fd-01~06），本文是收口复盘。

## 1. 架构改造全景

### 改造前（切片 #11 完成时）

首页已有三 tab UI（推荐/最新/关注），但"关注"tab 是**纯客户端过滤**：前端拿到全量
帖子列表（`fetchPosts({sort})`），然后用 `items.filter(guide => followed.includes(guide.author))`
过滤出已关注作者的帖子。两个结构性问题：

1. **不可扩展**：客户端要加载全量帖子才能筛出关注作者的帖子，数据量增长后不可用。
2. **数据不实**：关注 tab 的内容受限于客户端已加载的帖子范围，关注作者的新帖如果
   不在全量列表中就看不到。

### 改造后

- 新增 `feed/` 包：`FeedTimelineStore`（Redis ZSet）+ `FeedService`（读路径）+
  `FeedController`（端点）+ `FeedFanoutListener`（发帖扇出）。
- 发帖时（`PostPublishingService.publish()`）emit `PostPublishedEvent`，由
  `@TransactionalEventListener(AFTER_COMMIT)` 在事务提交后扇出：Feign 取粉丝列表 →
  Pipeline 批量 ZADD 到每个粉丝的 timeline ZSet。
- 读路径：`GET /api/v1/feed/following` → `ZREVRANGE` 取 postId → 委托
  `CommunityPostService.findPublishedByIds` 构建摘要（复用作者回填/媒体查询/标签逻辑）。
- 前端关注 tab 改调新端点（推荐/最新 tab 不变）。

### 变更清单（按工单）

| 工单 | 交付 | 关键文件 |
|---|---|---|
| fd-01 | FeedTimelineStore（Pipeline 批量 ZADD + 裁剪 + TTL） | feed/redis/FeedTimelineStore.java |
| fd-02 | PostPublishedEvent + FeedFanoutListener（AFTER_COMMIT 扇出） | feed/event/ |
| fd-03 | FeedService + FeedController（GET /feed/following） | feed/service/ + feed/web/ |
| fd-04 | 前端关注 tab 改调 fetchFollowingFeed | front/src/lib/api.ts + community-home.tsx |
| fd-05 | FeedTimelineIntegrationTest（7 测试） | feed/FeedTimelineIntegrationTest.java |
| fd-06 | 学习复盘 + Issue 关闭 | 本复盘 + PR #72 |

## 2. 关键难点解析

### 2.1 为什么选 Push-on-publish 而不是 Pull-on-read

Pull-on-read 模式：读时先查当前用户的关注列表（Feign → kuros-user），再逐作者查帖子
（N+1 跨服务调用）。100 个关注 = 100 次 Feign + 100 次 DB 查询，不可接受。

Push-on-publish 模式：发帖时一次性推给所有粉丝（N 次 ZADD），读时只需一次 ZREVRANGE
+ 一次 findAllById。读路径 O(log(N)+M)，N=timeline 容量、M=取回条数。

适用边界：粉丝数 <5000（写放大可控）、发帖频率中等。大 V（粉丝 >10w）需要推拉混合
（大 V 帖子不推，粉丝读时合并拉取），留 #13。

### 2.2 @TransactionalEventListener(AFTER_COMMIT) 的时序保证

为什么不在 `publish()` 里直接调用 `timelineStore.pushToTimelines()`？

① **解耦**：发帖服务不感知 feed 模块的存在（单向依赖 post → feed）。
② **时序保证**：AFTER_COMMIT 确保帖子已持久化到 DB。如果在事务内推，粉丝立即读
timeline → ZSet 里有 postId → 但 `findAllById` 查不到（事务还没提交）→ 返回空列表。
③ **异常隔离**：扇出失败（Feign 超时/Redis 抖动）不影响发帖成功响应。

### 2.3 Pipeline 批量写 vs 逐条写

100 个粉丝，逐条 ZADD = 100 次 RTT（每次 1ms → 100ms）。Pipeline 批量 = 1 次 RTT
（所有命令打包一次发送 → ~1ms）。粉丝数 > 100 时收益显著。

Pipeline 实现：`redisTemplate.executePipelined((RedisCallback) connection -> { ... })`
→ connection 直连 Redis 底层，绕过 StringRedisTemplate 的逐条 API。

### 2.4 ZSet score 选择：epoch millis vs 自增 ID

epoch millis（publishedAt 转 UTC 毫秒）作为 score：
- 天然按时间排序（ZREVRANGE 自然按时间倒序）
- 可读性（调试时 `ZSCORE key member` 能反推发布时间）
- 缺点：同一毫秒发布的帖子 score 相同（极端情况，本项目不会发生）

备选方案：自增序列号（INCR feed:global:seq）——需要额外 Redis 调用，且 score 不直观。

### 2.5 为什么委托 CommunityPostService 而不自己构造摘要

`PostSummaryResponse` 的构造涉及：PostMediaRepository（图片 URL 拼装 + publicBaseUrl
前缀）+ MediaAssetRepository（批量查 asset）+ UserDirectoryFacade.toAuthor（作者昵称 +
降级占位"用户"）+ 标签排序 + coverUrl 提取。

FeedService 如果自己写一套，未来改 summary 格式时两处都要改。委托给
`CommunityPostService.findPublishedByIds(Page<String>)`，零分叉。

### 2.6 前端单行代码的 `//` 注释陷阱

Feed 组件是单行 minified 格式。在 `useEffect` 内部加 `// 注释` 会吃掉该行后面的
所有代码（`//` 是 end-of-line comment）。修复：改用 `/* ... */` 块注释。
教训：**单行 JSX/TSX 里永远用 `/* */` 而不是 `//`**。

## 3. 简历 STAR 写法（含"原方案为何不行" + 收益量化）

- **S（情境）**：内容社区首页有推荐/最新/关注三 tab，但关注 tab 是纯客户端过滤——
  前端拿到全量帖子后用 `filter` 按关注列表筛，不可扩展（数据量增长后客户端要加载
  全量帖子）、数据不实（关注作者的新帖不在全量列表中就看不到）。
- **T（任务）**：实现真正的关注流——发帖时推送到粉丝 timeline，读时只取粉丝的
  timeline，按时间倒序分页返回。要求**前端改动最小、发帖不受影响、可被面试深挖**。
- **原方案为何不行**：① 纯客户端过滤——不可扩展；② Pull-on-read（读时查关注列表
  再逐作者查帖子）——N+1 跨服务调用，100 关注 = 100 次 Feign + 100 次 DB；
  ③ 直接用 Kafka/RocketMQ 异步扇出——引入额外 MQ 链路复杂度，本项目数据规模不需要。
- **A（行动）**：
  - Redis ZSet Timeline（key=feed:timeline:{userId}，score=publishedAt epoch millis）
  - Push-on-publish：发帖事务提交后 @TransactionalEventListener(AFTER_COMMIT) 异步
    扇出，Feign 取粉丝列表 → Pipeline 批量 ZADD（一次 RTT 完成所有粉丝写入）
  - 读路径：ZREVRANGE 取 postId 分页 → 委托 CommunityPostService.findPublishedByIds
    （复用作者回填/媒体查询/标签逻辑，零分叉）
  - 容量控制：ZREMRANGEBYRANK 保留最近 500 条 + EXPIRE 30 天惰性过期
  - 前端关注 tab 改调专属端点（推荐/最新不变），降级空状态
- **R（结果，可验证的结构性收益）**：
  - **读路径复杂度**：从 O(N*M)（N=关注数、M=每作者帖子数）降为 O(log(500)+20)
    （ZSet range + 批量 DB 查）
  - **写放大可控**：发帖时 N 次 ZADD（N=粉丝数，上限 5000），Pipeline 一次 RTT
  - **发帖不受影响**：扇出在事务提交后异步执行，异常 log.warn 跳过，发帖 201 响应不变
  - **代码零分叉**：FeedService 委托 CommunityPostService，改 summary 格式只改一处
  - **编译级验证**：backend compile + test-compile + frontend tsc 全绿

## 4. 原理详解

### 4.1 发帖扇出全链路
```
PostPublishingService.publish(request, authorId)
   │  @Transactional
   ├─ validate → save(CommunityPost) → replaceMedia
   ├─ postsPublishedCounter.increment()
   ├─ eventPublisher.publishEvent(PostPublishedEvent(postId, authorId, publishedAt))
   │     (Spring 记录事件，等事务提交后分发)
   └─ return postService.findPublishedById(saved.getId())
   │
   ▼ 事务提交 (tx commit)
   │
   @TransactionalEventListener(phase = AFTER_COMMIT)
   FeedFanoutListener.onPostPublished(event)
   ├─ userDirectory.followers(authorId, 1, 5000) → Feign → kuros-user
   │     └─ 返回粉丝 ID 列表
   ├─ timelineStore.pushToTimelines(postId, publishedAt, followerIds)
   │     └─ Pipeline:
   │           for each followerId:
   │              ZADD feed:timeline:{followerId} score postId
   │              ZREMRANGEBYRANK feed:timeline:{followerId} 0 -(501)
   │              EXPIRE feed:timeline:{followerId} 2592000
   └─ log.info("帖子 {} 扇出到 {} 个粉丝", postId, followerIds.size())
```

### 4.2 关注流读路径
```
GET /api/v1/feed/following?page=1&pageSize=20
   │  SaToken checkLogin (401 if not logged in)
   ▼
FeedController.following()
   └─ FeedService.findFollowingFeed(userId, 1, 20)
         ├─ timelineStore.readTimeline(userId, 0, 20)
         │     └─ ZREVRANGE feed:timeline:{userId} 0 19 → [postId-20, ..., postId-1]
         ├─ postService.findPublishedByIds(PageImpl(postIds))
         │     ├─ postRepository.findAllById(postIds) → 批量 DB 查
         │     ├─ userDirectory.findAuthors(authorIds) → Feign 批量取作者
         │     └─ post → toSummary (媒体/标签/作者回填)
         └─ return PageResult(items, meta)
```

### 4.3 ZSet 容量裁剪原理

`ZREMRANGEBYRANK key 0 -(maxSize+1)`：
- ZSet 按 score 升序排列（index 0 = score 最低 = 最旧）
- `-(maxSize+1)` 表示"从末尾倒数第 maxSize+1 个"
- 效果：删掉所有排名在 maxSize 之外的旧帖子，只保留最近 maxSize 条

例如 maxSize=500，ZSet 有 501 条：
- ZREMRANGEBYRANK key 0 -501 → 删掉 score 最低的那 1 条 → 剩余 500 条

## 5. 面试追问链回答清单（≥5 条）

**Q1：为什么选 Redis ZSet 而不是 MySQL 表存 timeline？**
A：timeline 是**写多读多、按时间排序、需要分页**的场景。MySQL 表需要索引维护 +
磁盘 IO，ZSet 在内存里 O(log(N)) 插入 + O(log(N)+M) 范围查询，且天然按 score
排序无需 ORDER BY。Redis 单线程模型保证 ZADD 原子性，无并发锁竞争。
微博/Twitter/Instagram 的 timeline 都用类似架构（内存 + 持久化双写）。

**Q2：发帖时如果粉丝数超 5000 怎么办？**
A：当前简化策略：只取前 5000 粉丝（分页 page=1, pageSize=5000），超出的不推。
生产级方案是**推拉混合**：活跃粉丝（最近 7 天登录过）推 timeline，非活跃粉丝
读时 Pull（查关注列表再查帖子）。判断活跃度的依据是 Redis 里的 `user:active:{userId}`
key（登录时 SET + TTL 7d）。本切片 scope 只覆盖 Push，混合留 #13。

**Q3：@TransactionalEventListener 如果事务回滚会怎样？**
A：`phase = AFTER_COMMIT` 只在事务**成功提交后**触发。如果事务回滚（发帖失败），
事件**不会**被分发，粉丝 timeline 不会被推入。这是 AFTER_COMMIT 的核心价值——
保证 timeline 里的 postId 在 DB 已可查。如果改成 `AFTER_COMPLETION`，回滚也会触发
（粉丝 timeline 有 postId 但 DB 查不到 → 关注流出现"空帖子"）。

**Q4：取消关注后，timeline 里还有该作者的帖子怎么办？**
A：当前已知局限（spec D5）——timeline ZSet 里仍保留该作者的历史 postId。读路径
用 `findAllById` 能查到帖子（因为帖子没被删），所以取消关注后仍能看到。
完整清理留 #13：取消关注时触发 `ZREMRANGEBYLEX feed:timeline:{userId} [postIds-of-author`
（需要 ZSet member 带 authorId 前缀才能用 LEX 范围删除）。

**Q5：Pipeline 和 MULTI/EXEC 事务有什么区别？**
A：Pipeline 是**批量发送**（多个命令打包一次 RTT，各自独立执行，不保证原子性）。
MULTI/EXEC 是**事务**（命令队列化，EXEC 时一次性原子执行，中间不被其他客户端插入）。
本切片场景（对 N 个粉丝的 ZADD）不需要原子性（某个粉丝写失败不影响其他粉丝），
只需要减少 RTT——Pipeline 更合适（无 WATCH/UNWATCH 开销、无 CAS 重试）。

**Q6（加分）：为什么 score 用 epoch millis 而不是自增序列？**
A：epoch millis 直接对应发布时间，`ZSCORE key postId` 能反推帖子发布时间（调试友好）。
自增序列需要额外 `INCR feed:global:seq` 调用（每次发帖多一次 Redis RTT），且 score
不直观（看到 score=12345 不知道对应什么时间）。缺点：同一毫秒发布的帖子 score 相同，
但本项目发帖频率不会到毫秒级并发，实际不会出现。

## 6. 技术选型对比

### 6.1 Feed 流架构方案对比

| 维度 | Pull-on-read | Push-on-publish（本项目） | Push/Pull 混合 | MQ 异步扇出 |
|---|---|---|---|---|
| 读延迟 | 高（N+1 跨服务） | 低（一次 ZREVRANGE + 批量 DB） | 低（合并 timeline + 拉大 V） | 同 Push |
| 写延迟 | 低（不写 timeline） | 中（N 次 ZADD，Pipeline 1 RTT） | 中（只推活跃粉丝） | 低（发帖即返回） |
| 写放大 | 零 | N（粉丝数） | M（活跃粉丝数） | N（MQ 消息数） |
| 实时性 | 实时（读时算） | 近实时（事务提交后可见） | 近实时（活跃粉丝） | 最终一致（MQ 延迟） |
| 复杂度 | 低 | 中 | 高（需活跃度判断） | 高（MQ + 补偿） |
| 适用边界 | 关注少、读少 | **粉丝 <5000、发帖中等**（本项目） | 大 V + 海量用户 | 超大规模/解耦需求 |

### 6.2 关键决策的备选方案（ADR 0005）

| 决策点 | 选定 | 拒绝的备选 | 拒绝理由 |
|---|---|---|---|
| 存储 | Redis ZSet | MySQL timeline 表 | 内存 O(log N) 优于磁盘 IO；天然排序 |
| 扇出时机 | AFTER_COMMIT | 事务内直接调用 | 保证 postId 在 DB 可查；异常隔离 |
| 批量写 | Pipeline | 逐条 ZADD | RTT 从 N 次降为 1 次 |
| 摘要构建 | 委托 CommunityPostService | 自己写一套 | 零分叉，改格式只改一处 |
| score | epoch millis | 自增序列 | 可读性好，无需额外 Redis 调用 |
| 容量控制 | ZREMRANGEBYRANK + TTL | 无限制 | 内存可控（500 条 × 30 天） |

## 7. 面试叙事模板

**30 秒电梯版**：我实现了一个社交社区的 Feed 关注流，用 Redis ZSet 做 Timeline。
发帖时事务提交后异步把 postId 推到所有粉丝的 ZSet（Pipeline 批量，一次 RTT），
粉丝读时 ZREVRANGE 按时间倒序取 postId 分页再查帖子详情。容量 500 条 + 30 天 TTL
惰性过期，超粉丝上限 5000 的大 V 场景留推拉混合。前端关注 tab 改调专属端点，
推荐/最新流不变。

**2 分钟详细版**：在 30 秒版基础上补充——
① **为什么此刻实现**：前置积木就绪——服务拆分（#10）后关注数据在 kuros-user，
内容在 kuros-backend，Feign 批量接口已就绪；互动异步化（#11）引入了事件驱动模式，
本切片复用 Spring ApplicationEvent 做发帖扇出；
② **为什么 Push 不 Pull**：Pull 要 100 次 Feign + 100 次 DB（N+1 跨服务），Push
一次 Pipeline 完成所有粉丝写入，读路径 O(log(500)+20)；
③ **AFTER_COMMIT 的时序保证**：如果在事务内推，粉丝立即读 → ZSet 有 postId 但
DB 查不到（事务没提交）→ 空列表。AFTER_COMMIT 保证推入的 postId 在 DB 已可查；
④ **为什么委托 CommunityPostService**：PostSummaryResponse 构造涉及图片 URL 拼装
+ 作者昵称降级 + 标签排序，自己写一套会导致两处维护；
⑤ **Pipeline vs MULTI/EXEC**：Pipeline 是批量发送（减少 RTT，各自独立），
MULTI/EXEC 是事务（原子执行）。本场景不需要原子性（某粉丝写失败不影响其他），
只需减少 RTT——Pipeline 更合适；
⑥ **已知局限**：取消关注后 timeline 残留帖子（#13 清理）、大 V 推拉混合（#13）、
热度衰减算法（#13）；
⑦ **收益验证**：读路径从 O(N*M) 降为 O(log(500)+20)，写放大可控（Pipeline 一次
RTT），发帖不受影响（异常隔离），代码零分叉，全部可编译验证。
