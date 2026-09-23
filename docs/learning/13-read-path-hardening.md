# 切片 #13：读路径加固——两级缓存 + 互斥锁防击穿 + 游标分页

> 承接 #11（互动写路径异步化）、#12（Feed ZSet Timeline），本切片补上「高并发内容社区」
> 叙事的最后一块——**读路径**。三个结构性难点咬合解决：① 热帖缓存击穿（互斥锁重建 +
> 空值哨兵防穿透）；② 深分页退化（keyset 游标分页替代 offset）；③ 单层缓存局限
> （L1 Caffeine + L2 Redis 两级 + pub/sub 跨节点失效）。共 8 个工单（rp-01~08），本文是收口复盘。
>
> **纪律声明**：本文性能数字全部来自 `ReadPathPerfBenchmark` 的实测输出（前缀 `[PERF]`），
> 无压测数据处只做结构性论证，**严禁编造 TPS/RT/并发数字**（CONTEXT.md 方向纠偏）。

## 1. 架构迁移全景

### 改造前（切片 #12 完成时）

读路径仍是 #3 时期的形态，暴露三个结构性矛盾（本项目无生产流量，为结构性论证，非事故复盘）：

1. **缓存击穿**：热帖 `postDetail`（裸 Caffeine L1，60s 物理过期）过期瞬间，N 个并发请求
   同时 miss → 全部穿透 DB 重建（post + author + media 三次查询）→ 连接池/行锁被打满。
   裸 `@Cacheable` 无任何重建保护，越热的帖子过期时冲击越大。
2. **深分页退化**：所有列表走 offset 分页（`LIMIT offset, n`）。DB 必须扫描并丢弃前 offset 行，
   复杂度 O(offset+n)；翻到第 1000 页要扫 1000×n 行。Feed 的 `ZREVRANGE offset, offset+limit-1` 同理。
3. **单层缓存局限**：仅 Caffeine L1（进程内），多节点不共享、服务重启即全部失效、L1 miss 直接打 DB。

### 改造后

- **两级缓存读流**：L1 Caffeine（纳秒，进程内，TTL 10s）→ L2 Redis（毫秒，跨节点共享，JSON，TTL 60s）
  → DB（互斥重建）。L1 命中直接返回；L1 miss 查 L2，命中回填 L1；L2 miss 才走 DB 重建并回填 L2 + L1。
- **互斥锁防击穿**：L2 miss → DB 重建前用 #5 的 `DistributedLock`（SET NX EX + Lua 释放）抢
  `lock:postDetail:{postId}`（每帖独立锁）；**双重检查**（拿锁后先重读 L2）；抢不到锁的线程自旋重读
  （50ms×3），耗尽则**降级直查 DB**（Redis 故障不阻断读）；锁 TTL 短（3s）防死锁。
- **空值哨兵防穿透**：查无结果缓存 `__NULL__`（TTL 30s），命中哨兵直接抛 `ResourceNotFoundException`，不打 DB。
- **pub/sub 跨节点 L1 失效**：写路径 → 删 L2 + `PUBLISH cache:evict:postDetail {postId}` → 各节点
  `CacheEvictionListener` 清本地 L1；L1 短 TTL 作丢消息兜底。
- **⭐计数与缓存解耦**（本切片最关键架构约束）：`postDetail` 只缓存**内容字段**（`PostDetailContent`），
  易变的 like/favorite 计数在组装 `PostDetailResponse` 时从 #11 实时 Redis 源
  （`InteractionRedisStore.readCount`）叠加，避免「两级缓存缓存了过期 DB 列计数」与「#11 实时计数源」打架。
- **游标分页（keyset）**：新增 `CursorPageResult<T>(items, nextCursor, hasMore)`（**不含 total**，省掉 `COUNT(*)`）
  + 不透明 base64url cursor。Feed 走 `ZREVRANGEBYSCORE`；帖子列表 latest/hot 走排序键元组 keyset 谓词。
  offset 端点保留兼容（同端点以是否传 `limit` 区分模式）。
- **前端**：Feed 关注 tab 改 cursor 无限滚动（「加载更多」透传 nextCursor，按 id 去重追加）；
  列表页保留页码 UI（后端 cursor 就绪，本片不强制迁）。

### 变更清单（按工单）

| 工单 | 交付 | 关键文件 |
|---|---|---|
| rp-01 | 两级缓存读组件 + postDetail/publicProfile 两级读 + 计数解耦 + 写路径驱逐迁移 | shared/cache/TwoLevelCache.java、post/api/PostDetailContent.java、CommunityPostService/ProfileService、CacheNames |
| rp-02 | 互斥锁防击穿（双重检查 + 自旋重读 + 降级直查）+ 空值哨兵防穿透 | TwoLevelCache.java、application.properties、CacheBreakdownIntegrationTest |
| rp-03 | 写路径删 L2 + pub/sub 广播失效各节点 L1 + 短 TTL 兜底 | TwoLevelCache.evict、shared/cache/CacheEvictionListener、shared/config/CachePubSubConfig、CacheEvictionPubSubIntegrationTest |
| rp-04 | CursorPageResult 契约 + base64 cursor 编解码 + Feed ZREVRANGEBYSCORE 游标读 + 端点 | shared/api/CursorPageResult、CursorCodec、FeedTimelineStore、FeedService、FeedController、FeedCursorIntegrationTest |
| rp-05 | 帖子列表 keyset 查询（latest/hot）+ findPublishedByCursor + /posts cursor 端点 | CommunityPostRepository、CommunityPostService、CommunityPostController、PostListCursorIntegrationTest |
| rp-06 | 前端 Feed 关注 tab cursor 无限滚动（加载更多透传 nextCursor） | front/src/lib/api.ts、community-home.tsx、globals.css |
| rp-07 | HTTP 契约测试（MockMvc）+ 性能基准 harness | cursor/CursorHttpContractIntegrationTest、perf/ReadPathPerfBenchmark |
| rp-08 | 学习复盘 + Issue 回写 + PR | 本文 + README + PR |

## 2. 关键难点解析

### 2.1 击穿 / 穿透 / 雪崩的区别与本切片对策

| 现象 | 定义 | 本切片对策 |
|---|---|---|
| 缓存**击穿** | 单个**热点** key 过期瞬间，大量并发同时 miss 穿透 DB | 互斥锁重建（每帖独立锁 + 双重检查 + 自旋重读 + 降级直查） |
| 缓存**穿透** | 查**根本不存在**的数据，缓存永远 miss，每次打 DB | 空值哨兵 `__NULL__`（短 TTL 30s），命中即抛 404 不查 DB |
| 缓存**雪崩** | 大量 key **同时**过期 / Redis 宕机，请求全压 DB | L1/L2 TTL 天然错开 + 降级直查不阻断；本片未做全量随机 TTL（数据量小，非热点） |

三者常被混为一谈，面试要先分清：**击穿是「一个热 key」，穿透是「不存在的 key」，雪崩是「一大片 key」**。

### 2.2 互斥锁重建 vs 逻辑过期

- **互斥锁（选定）**：L2 miss 时抢分布式锁，只有一个线程穿透 DB 重建，其余自旋等结果。
  优点：返回的永远是最新值；缺点：重建期间未抢到锁的线程短暂等待（自旋窗口 150ms）。
- **逻辑过期（拒绝）**：value 内嵌逻辑过期时间，过期后异步重建、期间返回旧值。
  优点：读永不阻塞；缺点：**返回 stale 数据**——`postDetail` 含内容，返回旧值误导用户；
  且需额外异步重建线程池 + 「谁触发重建」的协调，复杂度高于收益。

关键工程细节：**降级直查**。自旋窗口耗尽仍 miss（持锁者卡在慢 DB / Redis 抖动）时，
不再无限等待，直接查 DB 返回（但**不回填**缓存，避免与持锁者的回填竞争写脏值）——
保证「Redis 故障不阻断读」这条可用性底线。

### 2.3 为什么计数必须与缓存解耦（本切片最易踩的坑）

#11 把互动计数改成了 Redis 实时源 + RocketMQ 异步落库。若 `postDetail` 缓存整个含计数的
`PostDetailResponse`，会出现：用户点赞 → #11 实时 Redis 计数 +1 → 但详情缓存里还是**旧的 DB 列计数**
→ 详情页显示旧值直到缓存驱逐。两个「计数真相」打架。

解法：缓存**只存内容字段**（`PostDetailContent`：标题/正文/作者/媒体/分类/标签/发布时间），
计数在组装响应时用 `InteractionRedisStore.readCount(kind, id, dbFallback)` 实时叠加。
副作用（已在 rp-03 工单记录的 spec D4 字面偏差）：`InteractionProjectionService` 落库时
**不再驱逐**详情缓存——因为计数根本不进缓存，无需为计数变更失效内容缓存。

### 2.4 offset 分页 vs keyset 游标分页

- **offset**：`LIMIT n OFFSET k`——DB 扫描并丢弃前 k 行，O(k+n)，越深越慢；且 `PageResult` 需要
  `COUNT(*)` 全表计数（深分页慢的另一半根因）。
- **keyset（游标）**：`WHERE (排序键元组) < (cursor 元组) ORDER BY 排序键 DESC LIMIT n`——走索引 seek，
  O(log N + n) 与翻页深度**无关**；`CursorPageResult` **不含 total**，彻底省掉 `COUNT(*)`。

代价：游标**不支持跳页**（只能「下一页」），符合 feed/列表的产品语义。

实现要点：
- **排序键必须含唯一兜底维**：latest = (published_at, **id**)，hot = (like_count, comment_count, published_at, **id**)。
  id 兜底保证稳定全序——同 published_at / 同热度时翻页不丢不重。
- **JPQL 无 row-value 比较**：hot 四元组 `<` 展开为 OR 链：
  `like<cLike OR (= AND comment<cComment) OR (= = AND publishedAt<cPub) OR (= = = AND id<cId)`。
- **首页退化**：`:hasCursor=false` 时首析取项恒真，谓词整体为真 → 退化为「取第一页」。
- **返回 `List` 而非 `Page`**：避开 Spring Data 为 `Page` 自动附带的 `COUNT(*)`。

### 2.5 Feed ZSet 游标的同分去重

`ZREVRANGEBYSCORE key (cursorScore -inf LIMIT 0 n` 用**闭区间 max + 多取一 limit 缓冲**读下一页。
ZSet 同分成员按**字典序倒序**排列，故同一 score 下需靠 `postId.compareTo(cursorPostId)` 兜底跳过已读边界。
UUID 均为 ASCII，UTF-16 码元序 == 字节序，`compareTo` 与 Redis 的字典序一致，去重可靠。

### 2.6 两级缓存一致性：pub/sub + 短 TTL 双保险

写路径 `evict()` 三步：清本节点 L1 → 删共享 L2 → `PUBLISH cache:evict:{cacheName} {key}`。
各节点 `CacheEvictionListener` 收到消息后只清**本地 L1**（`evictLocalL1`，不再重复删 L2/广播，避免风暴）。
pub/sub **不保证送达**（Redis 断连期间消息丢失）→ L1 短 TTL（10s）作兜底，最长 10s 收敛。
`RedisMessageListenerContainer` 用 `PatternTopic(cache:evict:*)` 订阅所有缓存名的失效通道。

### 2.7 Spring Boot 4 = Jackson 3 的序列化陷阱

Spring Boot 4 默认 Jackson 3（`tools.jackson.*`），容器里**没有** Jackson 2 的 `ObjectMapper` bean
（Jackson 2 仅由 sa-token-redis-jackson 显式引入）。`TwoLevelCache` 做 L2 JSON 序列化时**自建** static
`ObjectMapper`（注册 `JavaTimeModule` + 关闭 `WRITE_DATES_AS_TIMESTAMPS`），不注入容器 bean。

### 2.8 @EnableCaching 条件装配陷阱

`@EnableCaching` 只在 `CacheConfig`（`@ConditionalOnExpression type != none`）上。当 `spring.cache.type=none`
时**没有 CacheManager bean** → `TwoLevelCache` 用 `ObjectProvider<CacheManager>.getIfAvailable()` 可选注入，
L1 为 null 时退化为纯 L2 路径（测试正是利用这点，用未登记 cacheName 走纯 L2 检验互斥锁）。

## 3. 简历 STAR 写法

- **S（情境）**：高并发内容社区的读路径（热帖详情、Feed 关注流、帖子列表）是最高频入口，但仍是
  裸 Caffeine L1 + 全 offset 分页：热帖过期瞬间并发穿透 DB、深翻页 O(offset) 退化、单层缓存多节点不共享。
- **T（任务）**：系统性加固读路径，与 #11 写路径异步化形成读写闭环，要求**可被面试深挖、收益可验证、不编造数字**。
- **原方案为何不行**：① 裸 `@Cacheable` 无重建保护——越热的帖子过期冲击越大；② 逻辑过期——返回 stale
  内容误导用户 + 异步重建协调复杂；③ offset 保留 total——`COUNT(*)` 是深分页慢的另一半根因，丢不掉；
  ④ 缓存整个含计数的响应——与 #11 实时计数源打架；⑤ 布隆过滤器防穿透——需维护全量位图 + 误判率，数据量小不划算。
- **A（行动）**：
  - **两级缓存**：L1 Caffeine(10s) + L2 Redis JSON(60s)，读流 L1→L2→DB 逐层回填；publicProfile 纳入 L2 省 Feign 往返。
  - **互斥锁防击穿**：复用 #5 `DistributedLock`（SET NX EX + Lua 释放），每帖独立锁 + 双重检查 + 自旋重读(50ms×3) + 降级直查。
  - **空值哨兵防穿透**：查无缓存 `__NULL__`(30s)，命中即抛 404。
  - **计数解耦**：详情只缓存 `PostDetailContent`，计数从 #11 实时 Redis 源叠加。
  - **pub/sub 跨节点失效**：写路径删 L2 + `PUBLISH cache:evict:*`，各节点清本地 L1，短 TTL 兜底丢消息。
  - **keyset 游标分页**：`CursorPageResult`(不含 total) + 不透明 base64url cursor；Feed 走 `ZREVRANGEBYSCORE`，
    列表 latest/hot 走排序键元组比较（id 兜底全序）；offset 端点以 `limit` 参数区分模式并存。
  - **前端**：Feed 关注 tab 无限滚动（加载更多透传 nextCursor，按 id 去重追加，hasMore=false 显示到底提示）。
- **R（结果，可验证的结构性收益）**：
  - **击穿收敛**：并发测试实证同一冷键 N 线程只重建 **1 次**（`CacheBreakdownIntegrationTest` 16 线程 rebuild==1）；
    性能基准 `ReadPathPerfBenchmark` 量化 64→1（DB 峰值负载下降百分比见 §5 实测表）。
  - **深翻页解耦**：cursor 翻页耗时与深度**无关**（keyset 索引 seek），offset 随 page 线性上涨——实测对比见 §5。
  - **省掉全表计数**：cursor 端点不执行 `COUNT(*)`，`meta` 不序列化（`@JsonInclude(NON_NULL)`）。
  - **跨节点准实时一致**：pub/sub 失效 + L1 短 TTL 兜底（最长 10s 收敛）。
  - **零破坏兼容**：offset 端点/契约全保留，HTTP 契约测试实证两套信封并存。
  - **验证**：backend `test-compile` EXIT=0、frontend `lint`+`build` 全绿；集成测试全量交 CI（见 §5 HANDOFF）。

## 4. 原理详解

### 4.1 两级缓存读流 + 互斥重建
```
CommunityPostService.findPublishedById(id)
   └─ twoLevelCache.get(POST_DETAIL, id, PostDetailContent.class, loader)
         ├─ !enabled → 直接 loader.get()（透传）
         ├─ L1 get → hit? 返回
         ├─ readL2 → L2Result:
         │     HIT          → 回填 L1 → 返回
         │     NULL_SENTINEL → 返回 null（上层抛 404）
         │     MISS         → rebuild()
         └─ rebuild():
               tryLock(lock:POST_DETAIL:id, 3s)
               ├─ 抢到 → 双重检查 readL2（可能已被重建）
               │         仍 MISS → loader.get()
               │                   非 null → writeL2(TTL 60s) + 回填 L1
               │                   null    → writeNullSentinel(TTL 30s)
               │         finally unlock(Lua)
               └─ 没抢到 → 自旋 spinRetries 次（sleep 50ms + readL2）
                           HIT/NULL_SENTINEL → 返回
                           耗尽仍 MISS → 降级 loader.get()（不回填）
   └─ content==null → throw ResourceNotFoundException
   └─ likeCount  = interactionRedisStore.readCount(LIKE, id, dbFallback)   ← 实时叠加，不进缓存
      favoriteCount = interactionRedisStore.readCount(FAVORITE, id, dbFallback)
   └─ assembleDetail(content, likeCount, favoriteCount)
```

### 4.2 pub/sub 跨节点 L1 失效
```
写路径（帖子 update/delete、评论增删）→ twoLevelCache.evict(POST_DETAIL, id)
   ├─ evictLocalL1(cacheName, key)          （清本节点 L1）
   ├─ redis.delete(cache:POST_DETAIL:id)     （删共享 L2，含可能的空值哨兵）
   └─ redis.convertAndSend(cache:evict:POST_DETAIL, id)  （广播）
         │
         ▼ 各节点
   CacheEvictionListener.onMessage(channel, body)
   └─ 解析 cacheName + key → twoLevelCache.evictLocalL1(cacheName, key)  （只清本地 L1，不再删 L2/广播）

RedisMessageListenerContainer（CachePubSubConfig，@ConditionalOnExpression type != none）
   └─ addMessageListener(listener, PatternTopic("cache:evict:*"))
```

### 4.3 keyset 游标分页（帖子列表 latest）
```
GET /api/v1/posts?sort=latest&limit=n[&cursor=c]
   └─ CommunityPostService.findPublishedByCursor(sort, cat, tag, kw, cursor, limit)
         ├─ normalizedLimit = clamp(limit)
         ├─ decodeLatestCursor(cursor) → LatestCursor(publishedAt, id) | null
         ├─ repository.findLatestByCursor(status, ..., hasCursor, cursorPublishedAt, cursorId,
         │                                PageRequest.of(0, normalizedLimit+1))   ← 多取一条探测 hasMore
         │     WHERE ... AND (:hasCursor=false
         │                    OR p.publishedAt < :cursorPublishedAt
         │                    OR (p.publishedAt = :cursorPublishedAt AND p.id < :cursorId))
         │     ORDER BY p.publishedAt DESC, p.id DESC
         ├─ hasMore = rows.size() > normalizedLimit
         ├─ page = hasMore ? rows.subList(0, normalizedLimit) : rows
         ├─ authors 批量回填 → toSummary
         └─ nextCursor = hasMore ? encodeCursor(sort, page.last) : null
   └─ new ApiResponse<>(CursorPageResult(items, nextCursor, hasMore), null)  ← meta=null 不序列化
```

### 4.4 Feed ZSet 游标读
```
GET /api/v1/feed/following?limit=n[&cursor=c]   （SaToken checkLogin，未登录 401）
   └─ FeedService.findFollowingFeedByCursor(userId, cursor, limit)
         ├─ decodeFeedCursor(cursor) → FeedCursor(score, postId) | null
         ├─ timelineStore.readTimelineByCursor(userId, cursorScore, cursorPostId, limit+1)
         │     firstPage = cursorScore==null
         │     max = firstPage ? +inf : cursorScore
         │     fetchCount = firstPage ? limit : 2*limit          ← 同分边界缓冲
         │     ZREVRANGEBYSCORE key -inf max LIMIT 0 fetchCount (with scores)
         │     跳过：!firstPage && score==max && id.compareTo(cursorPostId) >= 0
         ├─ hasMore = ids.size() > normalizedLimit；pageIds = 前 normalizedLimit
         ├─ nextCursor = encode(pageIds.last + scoreOf(userId, last))
         └─ findPublishedByIds(PageImpl(pageIds)).items()  ← 复用 #12 摘要构建，零分叉
```

## 5. 实测收益（rp-07 性能数据）

> **采集方式**：`ReadPathPerfBenchmark`（`@EnabledIfSystemProperty(perf=true)` 门控，默认不进 CI）。
> 运行命令：`cd kuros-backend && ./mvnw.cmd test '-Dtest=ReadPathPerfBenchmark' '-Dperf=true' '-Dperf.posts=5000'`
> **环境诚实声明**：数据来自 Testcontainers Redis + 进程内 H2（采集于 2026-09-23，N=5000、pageSize=20、repeat=50），
> 展示「随规模变化的趋势与量级差异」，非生产 MySQL 绝对 RT；生产绝对值需在 compose（MySQL 8）环境用同法采集。

### 5.1 击穿保护 DB 重建次数（并发=64）

| 场景 | DB 重建次数 | 说明 |
|---|---|---|
| 有互斥锁保护 | **1** | 惊群被收敛，`assertThat(rebuilds==1)` 兼作正确性断言 |
| 无保护（直查） | **64** | 缓存失效瞬间 64 并发全部穿透 DB |
| 收敛倍数 / 峰值下降 | **64 → 1（DB 峰值负载下降 98.4%）** | `[PERF]` 输出直接给出 |

### 5.2 offset 深翻页 vs cursor 同深度耗时（N=5000, pageSize=20, repeat=50, env=Testcontainers-Redis+H2）

| page | offset avg(ms) | cursor avg(ms) | speedup |
|---|---|---|---|
| 10 | 45.532 | 21.811 | 2.09x |
| 100 | 26.656 | 21.594 | 1.23x |
| 250（最深） | 33.512 | **3.840** | **8.73x** |

> **数据诚实解读**：H2 进程内测量有明显噪声——offset 列非单调（45→27→34ms），因为浅深度时 `OFFSET`
> 扫描成本相对于每次调用的固定开销（作者批量回填、摘要构建、计数实时读）很小，被 JIT/GC 抖动淹没。
> **趋势在最深页（page 250）最清晰**：cursor 降到 3.84ms（keyset 索引 seek，与深度无关），offset 仍 33.5ms
> （`OFFSET 4980` 扫描丢弃），speedup 8.73x。生产 MySQL 下 offset 随深度**单调上涨**会更显著（真实磁盘/缓冲页扫描），
> 此处 H2 数字只作量级与趋势证据，不作线上 RT 承诺。

### 5.3 测试覆盖清单（结构性指标，可复现）

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| TwoLevelCacheIntegrationTest | 3 | L1/L2 命中与回填、计数解耦 |
| CacheIntegrationTest | 2 | 既有缓存回归 |
| CacheBreakdownIntegrationTest | 3 | 击穿 rebuild==1、空值哨兵、404 哨兵写入 |
| CacheEvictionPubSubIntegrationTest | 2 | evict 删 L2 + 广播、订阅者清本地 L1 |
| FeedCursorIntegrationTest | 5 | Feed 游标遍历不丢不重、同分兜底、hasMore/nextCursor、空页、非法游标 |
| PostListCursorIntegrationTest | 6 | latest/hot keyset 遍历、同排序键 id 兜底、hasMore、offset 兼容、非法游标 |
| CursorHttpContractIntegrationTest | 5 | HTTP 契约：游标信封 + meta 不序列化、offset 兼容、400 INVALID_CURSOR、401 拦截 |

> **全量后端套件（2026-09-23本地实跑，等价 CI `./mvnw test`）**：`Tests run: 83, Failures: 0, Errors: 0, Skipped: 2`，BUILD SUCCESS。
> 2 skipped = `InteractionRocketMQIntegrationTest`（需真实 RocketMQ，既有性跳过）；`ReadPathPerfBenchmark` 被 `perf` 门控排除在常规套件外（未计入）。
> 前端 `npm run lint`（0 error）+ `npm run build`（Compiled successfully + TypeScript 通过）均绿。

## 6. 面试追问链回答清单（≥5 条）

**Q1：缓存击穿、穿透、雪崩怎么区分？你分别怎么解决的？**
A：击穿是「一个热 key 过期瞬间并发穿透」→ 互斥锁重建（每帖独立锁 + 双重检查 + 自旋 + 降级直查）；
穿透是「查根本不存在的 key，缓存永远 miss」→ 空值哨兵 `__NULL__` 短 TTL；雪崩是「一大片 key 同时过期
或 Redis 宕机」→ L1/L2 TTL 天然错开 + 降级直查不阻断读。本片重点解决前两个（热点内容社区的真实矛盾）。

**Q2：互斥锁重建和逻辑过期怎么选？**
A：逻辑过期读永不阻塞但返回 stale 数据——`postDetail` 含正文内容，返回旧值误导用户，且需异步重建线程池 +
「谁触发重建」的协调，复杂度高于收益，拒。互斥锁返回的永远是最新值，代价是重建期间未抢到锁的线程短暂自旋
（窗口 150ms）；自旋耗尽降级直查（不回填）保证 Redis 故障不阻断读。

**Q3：为什么计数不进缓存？这不是多一次 Redis 读吗？**
A：因为 #11 已把计数改成 Redis 实时源 + MQ 异步落库。若缓存含计数的响应，用户点赞后详情会显示旧的 DB 列计数
直到缓存驱逐——两个「计数真相」打架。所以详情只缓存内容字段（`PostDetailContent`），计数用
`InteractionRedisStore.readCount` 实时叠加。多一次 Redis 读换语义正确，值得；且计数读源本身是 O(1) Redis。

**Q4：游标分页为什么不能跳页？offset 不是更灵活吗？**
A：keyset 游标的本质是「记住上一页最后的排序键元组，下一页从它之后开始 seek」，只能顺序前进。offset 能跳页
但代价是 `LIMIT n OFFSET k` 扫描丢弃前 k 行（O(k+n)）+ `COUNT(*)` 全表计数——这正是深分页慢的两个根因。
feed/列表的产品语义就是「无限往下翻」，不需要跳页，用游标换「翻页耗时与深度解耦 + 省掉 COUNT(*)」是划算的。

**Q5：hot 排序四元组游标，JPQL 怎么写？为什么不用 row-value？**
A：JPQL/HQL 不支持 `(a,b,c,d) < (?,?,?,?)` 这种 row-value 比较，只能展开为 OR 链：
`like<cLike OR (like=cLike AND comment<cComment) OR (... AND publishedAt<cPub) OR (... AND id<cId)`，
配合 `ORDER BY like DESC, comment DESC, publishedAt DESC, id DESC`。id 作最后一维兜底，保证同热度时
仍有稳定全序，翻页不丢不重。

**Q6：pub/sub 失效消息丢了怎么办？多节点会不会不一致？**
A：Redis pub/sub 是 fire-and-forget，不保证送达（断连期间消息丢失）。所以做了双保险：pub/sub 主动失效 +
L1 短 TTL（10s）被动兜底。最坏情况某节点漏收失效消息，本地 L1 最多 10s 后自然过期收敛。这是两级缓存
一致性的标准权衡——用短 TTL 限制不一致窗口，不追求强一致（内容社区可接受准实时）。

**Q7（加分）：Feed 游标同分（同一发布毫秒）怎么保证不丢不重？**
A：ZSet 同 score 的成员按字典序倒序排列。游标读时用闭区间 `max=cursorScore` + 多取一 limit 缓冲，
再对 `score==max` 的边界成员用 `postId.compareTo(cursorPostId) >= 0` 跳过已读的。UUID 全 ASCII，
UTF-16 码元序 == 字节序 == Redis 字典序，所以 `compareTo` 兜底可靠。

**Q8（加分）：Spring Boot 4 下 L2 的 JSON 序列化踩了什么坑？**
A：Spring Boot 4 默认 Jackson 3（`tools.jackson.*`），容器里没有 Jackson 2 的 `ObjectMapper` bean
（Jackson 2 只被 sa-token-redis-jackson 显式带进来）。所以 `TwoLevelCache` 自建 static `ObjectMapper`
（注册 `JavaTimeModule` + 关 `WRITE_DATES_AS_TIMESTAMPS`）做 L2 序列化，不注入容器 bean，避免版本错配。

## 7. 技术选型对比

### 7.1 防击穿方案对比

| 维度 | 裸 @Cacheable | 互斥锁重建（本项目） | 逻辑过期 |
|---|---|---|---|
| 数据新鲜度 | 最新 | 最新 | **stale（返回旧值）** |
| 击穿保护 | 无 | 有（1 次重建） | 有（异步重建） |
| 读阻塞 | 无 | 重建期间短暂自旋 | 无 |
| Redis 故障 | 直接打 DB | **降级直查不阻断** | 依赖异步线程池 |
| 复杂度 | 低 | 中（复用 #5 锁） | 高（线程池 + 触发协调） |
| 适用 | 冷数据 | **热点内容（本项目）** | 可容忍 stale 的场景 |

### 7.2 分页方案对比

| 维度 | offset 分页 | keyset 游标（本项目） |
|---|---|---|
| 深翻页复杂度 | O(offset+n) | **O(log N + n)，与深度无关** |
| 是否 COUNT(*) | 需要（total） | **不需要** |
| 跳页 | 支持 | 不支持（只下一页） |
| 数据变动时 | 可能丢/重 | 排序键稳定则不丢不重 |
| 响应契约 | PageResult(total) | CursorPageResult(nextCursor,hasMore) |
| 适用 | 后台管理/需跳页 | **feed/列表无限滚动（本项目）** |

### 7.3 关键决策的备选方案（ADR 0006）

| 决策点 | 选定 | 拒绝的备选 | 拒绝理由 |
|---|---|---|---|
| 防击穿 | 互斥锁重建 | 逻辑过期 | 返回 stale 内容误导 + 异步协调复杂 |
| 防穿透 | 空值哨兵(30s) | 布隆过滤器 | 需维护全量位图 + 误判率，数据量小不划算 |
| 跨节点一致 | pub/sub + 短 TTL 兜底 | 仅 L1 短 TTL | 仅 TTL 不一致窗口=L1 TTL，pub/sub 是标准答案 |
| 详情缓存内容 | 只缓存内容 + 计数实时叠加 | 缓存整个含计数响应 | 与 #11 实时计数源打架 |
| cursor 响应 | 不含 total | offset+cursor 混合带 total | 保留 total 就丢不掉 COUNT(*)，违背初衷 |
| 游标覆盖范围 | 仅 Feed + 帖子列表 | 全部列表游标化 | 收藏/评论非热点，收益小改动大，稀释主叙事 |

## 8. 面试叙事模板

**30 秒电梯版**：我为高并发内容社区的读路径做了系统性加固。热帖详情用 L1 Caffeine + L2 Redis 两级缓存，
L2 miss 时用 Redis 互斥锁 + 双重检查重建，把过期瞬间的并发穿透收敛成一次 DB 重建，拿不到锁降级直查保证
Redis 故障不阻断读；查无的 id 用空值哨兵防穿透。关键约束是详情只缓存内容字段，易变的点赞计数从实时 Redis 源
叠加，不和异步计数源打架。分页用 keyset 游标替代 offset，Feed 走 ZREVRANGEBYSCORE、列表走排序键元组比较，
去掉 COUNT(*)，翻页耗时和深度解耦。多节点靠 Redis pub/sub 广播失效清 L1，短 TTL 兜底丢消息。

**2 分钟详细版**：在 30 秒版基础上补充——
① **为什么此刻做**：#11 把互动写路径异步化、#12 建了 Feed ZSet Timeline，读路径是「高并发内容社区」叙事的
最后一块，热帖读/Feed 读是社区最高频入口，与写路径形成完整闭环；
② **击穿/穿透/雪崩分清**：击穿是一个热 key（互斥锁），穿透是不存在的 key（空值哨兵），雪崩是一片 key
（TTL 错开 + 降级）——先分清再对症下药；
③ **互斥锁 vs 逻辑过期**：逻辑过期返回 stale 内容误导用户，且异步重建协调复杂；互斥锁返回最新值，
代价是短暂自旋，自旋耗尽降级直查（不回填）保证可用性；
④ **计数解耦这个坑**：缓存含计数的响应会和 #11 实时 Redis 计数源打架（点赞后详情显示旧值），
所以只缓存 PostDetailContent，计数 readCount 实时叠加；
⑤ **游标为什么快**：offset 的 `LIMIT n OFFSET k` 要扫丢前 k 行 + COUNT(*) 全表计数，keyset 走索引 seek
且不带 total，翻页耗时与深度无关；代价是不支持跳页（feed 语义不需要）；
⑥ **两级缓存一致性**：pub/sub 主动失效 + L1 短 TTL(10s) 兜底丢消息，用短 TTL 限制不一致窗口，不追求强一致；
⑦ **收益验证**：并发测试实证 rebuild==1，性能基准 ReadPathPerfBenchmark 实测 offset vs cursor 耗时表 +
64→1 重建次数（真实数据，禁止编造），HTTP 契约测试实证两套分页信封在同一端点并存零破坏。
