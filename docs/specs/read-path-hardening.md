# 读路径加固（两级缓存 + 互斥锁防击穿 + 游标分页）

> 切片 #13 · 关联 [ADR 0006](../adr/0006-read-path-hardening.md) · 承接 #11 写路径异步化、#12 Feed 流

## Problem Statement

#11 解决了互动**写**路径的热点行竞争，#12 建了 Feed 流模型，但**读**路径仍停留在 #3 时期的"裸 Caffeine L1 + 全 offset 分页"，存在三个结构性问题（本项目无生产流量，为结构性论证）：

1. **缓存击穿**：热帖 `postDetail`（Caffeine，60s 物理过期）过期瞬间，N 个并发请求同时 miss，全部穿透 DB 重建（post + author + media 三次查询），连接池/行锁被打满。越热的帖子过期时冲击越大，裸 `@Cacheable` 无重建保护。
2. **深分页退化**：所有列表走 offset 分页（`LIMIT offset, n`），DB 必须扫描并丢弃前 offset 行，复杂度 O(offset+n)；翻到第 1000 页要扫 1000×n 行。Feed 的 `ZREVRANGE offset` 同理。
3. **单层缓存局限**：仅 Caffeine L1（进程内），多节点不共享、重启即全失、L1 miss 直接打 DB，缺少跨节点共享的 L2 兜底层。

此外，#11 之后互动计数的权威实时源已是 Redis（`InteractionRedisStore`），而 `postDetail` 缓存嵌的是 DB 冗余列计数（异步落库有滞后）——若两级缓存把含计数的详情整个缓存，会与实时计数源打架、缓存到过期计数。

## Solution

读路径加固为 **两级缓存（L1 Caffeine + L2 Redis）+ 互斥锁防击穿 + 游标分页**，三者咬合，与 #11 写路径形成完整闭环：

- **两级缓存**：读路径 L1 Caffeine（纳秒、进程内、短 TTL）→ L2 Redis（毫秒、跨节点共享、长 TTL）→ DB；写路径删 L2 + Redis pub/sub 广播失效各节点 L1。
- **互斥锁防击穿**：L2 miss → DB 重建前抢每帖独立锁，双重检查，只放一个线程重建；拿不到锁自旋重读、仍 miss 降级直查（Redis 故障不阻断读）。
- **缓存与计数解耦**：`postDetail` 只缓存内容字段，互动计数在组装时从 #11 实时 Redis 源叠加，不进缓存。
- **空值哨兵防穿透**：查无结果的 postId 缓存空标记短 TTL，命中哨兵直接返回不存在，不打 DB。
- **游标分页**：Feed 关注流走 `ZREVRANGEBYSCORE`、帖子列表走排序键元组 keyset 比较，不透明 base64 cursor，响应不带 total（省掉 `COUNT(*)`）。

## User Stories

1. 作为用户，我想在热帖缓存过期的瞬间仍然快速拿到详情，而不是一起卡在数据库重建上，这样高并发下详情页依然稳定。
2. 作为用户，我想详情里的点赞/收藏数始终是最新的实时值，即使内容字段被缓存，这样我看到的计数不会滞后。
3. 作为用户，我想在服务重启后热帖详情依然能快速命中（L2 Redis 跨重启共享），这样冷启动不会瞬间打垮数据库。
4. 作为运维者，我想某个帖子被编辑/删除/互动落库后，所有节点的本地缓存都能准实时失效，这样多节点部署下不会读到旧内容。
5. 作为用户，我想反复查询一个不存在的帖子时系统不会被拖垮（空值哨兵拦截），这样恶意或错误的 id 不会持续穿透数据库。
6. 作为用户，我想在关注流里下拉加载更多时用游标翻页，这样无论翻到多深，加载速度都不变慢。
7. 作为用户，我想帖子列表（最新/热门）的深翻页也走游标，这样翻到第 1000 页和第 1 页一样快。
8. 作为前端开发者，我想游标分页返回 `nextCursor` 和 `hasMore`，这样我只需透传游标就能实现无限滚动，不必维护页码和总数。
9. 作为用户，我想 Feed 关注 tab 改成"加载更多"的无限滚动体验，这样浏览关注动态更连贯。
10. 作为开发者，我想旧的 offset 分页端点（作者页/收藏/评论）保持兼容不破坏，这样本切片只改两个热点、控制爆炸半径。
11. 作为用户，我想游标分页在排序键相同（如同一毫秒发布、同热度）时不丢帖、不重复，这样翻页结果稳定可信。
12. 作为面试者，我想这套读路径加固能讲清"击穿 vs 穿透 vs 雪崩"、"互斥锁 vs 逻辑过期"、"offset vs keyset"、"两级缓存一致性"的取舍，这样每个决策都能被深挖追问。

## Implementation Decisions

### D1: 两级缓存读流与层级职责

- **L1 Caffeine**：进程内，TTL 10s（短，限制跨节点不一致窗口），`maximumSize` 维持 1000。
- **L2 Redis**：String 结构，value = `PostDetailResponse` 内容字段的 JSON（Jackson 序列化），key = `cache:postDetail:{postId}`，TTL 60s。
- **读流**：L1 hit → 返回；L1 miss → L2 hit → 回填 L1 → 返回；L2 miss → 互斥锁重建（D2）→ 回填 L2 + L1 → 返回。
- 改造点：现有 `@Cacheable("postDetail")` 注解式单层缓存升级为显式两级缓存读取组件（注解无法表达 L1→L2→DB + 互斥重建 + 计数叠加的复合逻辑）。`publicProfile` 同样纳入 L2（省一次 Feign 往返），失效靠 TTL。

### D2: 互斥锁防击穿（重建保护）

- 复用 #5 `DistributedLock`（SET NX EX + Lua 释放），锁 key = `lock:postDetail:{postId}`，TTL 3s。
- **双重检查**：抢到锁后先重读 L2（可能已被其他线程重建），命中则直接返回，不查 DB。
- **拿不到锁**：短暂自旋重读 L2（如 50ms × 3 次）；仍 miss 则**降级直查 DB**（不无限等待，保证 Redis 故障时读路径不阻断）。
- 重建成功后回填 L2 + L1，释放锁。

### D3: 缓存与实时计数解耦（关键约束）

- `postDetail` 两级缓存**只缓存内容字段**：标题、正文、摘要、作者、媒体、分类、标签、发布时间、浏览数。
- **不缓存** `likeCount`/`favoriteCount`：组装 `PostDetailResponse` 时从 #11 `InteractionRedisStore.readCount(LIKE/FAVORITE, postId, dbBaseline)` 叠加实时值。
- 这样详情缓存永不因计数过期而失效，计数始终与 #11 实时源一致；缓存驱逐只由内容变更（编辑/删除）触发。

### D4: 跨节点 L1 一致性（pub/sub 广播失效）

- 写路径（帖子 update/delete、`InteractionProjectionService.apply` 落库、评论增删）→ 删 L2 key + `PUBLISH cache:evict:postDetail {postId}`。
- 各节点启动时订阅 `cache:evict:postDetail` 频道，收到消息清本地 L1 对应键。
- L1 短 TTL（10s）作为 pub/sub 丢消息（Redis 断连）的兜底，最长 10s 收敛。
- 复用现有 Redis，无需新中间件。

### D5: 缓存穿透防护（空值哨兵）

- 查无结果的 postId（DB 也无）→ 在 L2 写入空值哨兵（如特殊标记 `__NULL__`），TTL 30s。
- 读路径命中哨兵 → 直接抛 `ResourceNotFoundException`，不查 DB。
- 布隆过滤器仅进方案对比，本切片不实现（数据量小、维护成本高）。

### D6: 游标分页——Feed 关注流

- `FeedTimelineStore` 新增 `readTimelineByCursor(userId, cursorScore, cursorPostId, limit)`：用 `ZREVRANGEBYSCORE key (cursorScore -inf LIMIT 0 limit` 取 score 严格小于游标的成员；同 score 用 postId 字典序兜底去重（跳过已返回的边界成员）。
- cursor = (score, postId) 编码为不透明 base64。
- `FeedService.findFollowingFeedByCursor(userId, cursor, limit)` 返回 `CursorPageResult`，委托 `CommunityPostService.findPublishedByIds` 批量回填（沿用 #12 的过滤已删除逻辑）。
- 端点：`GET /api/v1/feed/following?cursor={c}&limit={n}`（cursor 为空表示第一页）；保留旧 `page/pageSize` 参数兼容或迁移（见 D9）。

### D7: 游标分页——帖子列表 latest/hot

- `CommunityPostRepository` 新增 keyset 查询：
  - latest：排序键 (published_at, id)，谓词 `WHERE (published_at < :ts) OR (published_at = :ts AND id < :id)`，`ORDER BY published_at DESC, id DESC LIMIT n`。
  - hot：排序键 (like_count, comment_count, published_at, id)，元组比较（row-value 或展开 OR），`ORDER BY` 同序。
- cursor = (排序键值..., id) 编码为不透明 base64。
- `CommunityPostService.findPublishedByCursor(sort, category, tag, keyword, cursor, limit)` 返回 `CursorPageResult`，作者批量回填沿用 `UserDirectoryFacade.findAuthors`。
- 端点：`GET /api/v1/posts?sort=hot|latest&cursor={c}&limit={n}`（与现有 offset 端点并存，见 D9）。

### D8: 响应契约 CursorPageResult

- 新增 `CursorPageResult<T>(List<T> items, String nextCursor, boolean hasMore)`，**不含 totalItems/totalPages**（游标分页不执行 `COUNT(*)`）。
- `nextCursor`：本页最后一条记录的游标编码；`hasMore=false` 时 `nextCursor` 为 null。
- 旧 `PageResult`/`PageMeta`（offset）保留给未迁移端点（作者页/收藏/评论），不破坏现有契约。

### D9: 兼容性与前端

- **后端**：cursor 与 offset 端点并存。帖子列表 `GET /api/v1/posts` 同时接受 `page/pageSize`（offset，返回 `PageResult`）与 `cursor/limit`（cursor，返回 `CursorPageResult`）——以是否传 `cursor` 区分模式。Feed 关注流同理。
- **前端**：Feed「关注」tab 改 cursor 无限滚动（"加载更多"按钮透传 `nextCursor`，追加到列表）；攻略/帖子列表页保留页码 UI，用 cursor 模拟"下一页"（最小改动），或后续再迁无限滚动。
- 详情页计数改由实时源叠加（D3），语义与 #11 一致，前端 `fetchPostInteractions` 行为不变。

## Testing Decisions

好的测试只验证外部行为（缓存命中/重建次数、翻页结果正确性、API 契约），不绑定实现细节（不断言内部私有方法、不mock 被测逻辑本身）。沿用项目既有 seam，不新增测试基础设施：

- **两级缓存 + 击穿 + 穿透 + pub/sub（service 级 seam）**：`@SpringBootTest` + Testcontainers Redis + H2 命名库，prior art 为 `CacheIntegrationTest`/`InteractionAsyncIntegrationTest`。
  - 击穿：N 个并发线程打同一"已过期/未缓存"热帖，断言 DB 重建只发生一次（用计数型 repository 包装或查询计数器），其余线程拿到同一结果。
  - 两级缓存：L1 miss → L2 hit → 回填 L1；L2 miss → DB → 回填 L2+L1；断言各层命中与回填。
  - 计数解耦：缓存内容后改 Redis 实时计数，断言详情返回的计数是最新实时值（缓存未污染计数）。
  - pub/sub 失效：写路径触发后断言 L2 被删 + 失效消息发出（可用第二个缓存实例/订阅者验证 L1 被清）。
  - 空值哨兵：查不存在 id 两次，断言第二次不查 DB（哨兵命中）且抛 `ResourceNotFoundException`。
- **游标分页（service + HTTP seam）**：
  - Feed cursor：`FeedTimelineIntegrationTest` 扩展，验证 `ZREVRANGEBYSCORE` 游标翻页正确、同 score 去重、不丢不重、深翻页与浅翻页结果一致。
  - 帖子列表 cursor：H2 种子多帖，验证 latest/hot 两种排序键的 keyset 翻页正确性、边界（同 published_at / 同热度）去重、`hasMore`/`nextCursor` 语义。
  - HTTP 契约：MockMvc（prior art `KurosBackendApplicationTests`）验证 cursor 端点返回 `CursorPageResult` 结构、cursor 透传翻页、offset 端点仍兼容。
- **主套件兼容**：新增缓存/游标逻辑不破坏现有 59 个测试；测试环境缓存开关沿用 `spring.cache.type` 与 Testcontainers 模式。
- **性能验证（DoD 硬要求，禁止编造并发数字）**：击穿保护前后 DB 重建次数对比、offset page=1000 vs cursor 同深度耗时对比，以实测数据写进 `docs/learning/13`；耗时压测交用户执行。

## Out of Scope

- 作者页/收藏/评论列表的游标化（非高并发热点，保留 offset）。
- 布隆过滤器防穿透（仅进方案对比）。
- 逻辑过期防击穿（已选互斥锁，逻辑过期进备选方案对比）。
- 热度衰减算法（推荐流 ranking 增强，#12 已挂起，留下一片）。
- 取消关注时清理 timeline（#12 已挂起，留下一片）。
- 缓存雪崩专项（大量键同时过期 → TTL 加随机抖动）；本片 TTL 固定，雪崩防护可作追问讨论但不实现。
- 全站前端分页重写为无限滚动（仅 Feed 关注 tab 改，列表页最小改动）。
- commentCount 失联缺陷的修复（#11 已知缺陷，独立工单）。

## Further Notes

- 三个难点（击穿/游标/两级缓存）在实现上咬合：两级缓存的 L2-miss 边界正是互斥锁保护点，游标分页减少 DB 扫描压力与缓存形成读路径合力。面试可串成"读路径高并发三件套"完整叙事。
- 关键追问点预埋：① 为什么互斥锁不用逻辑过期（计数准确性）；② 为什么详情缓存不存计数（与 #11 实时源解耦）；③ pub/sub 丢消息怎么办（L1 短 TTL 兜底）；④ 游标分页为什么不返回 total（省 `COUNT(*)`）；⑤ 同 score/同排序键翻页怎么去重（id 兜底全序）；⑥ 击穿 vs 穿透 vs 雪崩的区别。
- 前端已有三 tab UI（`community-home.tsx`），关注 tab 改无限滚动是局部改动。
- 本切片完成后，#11（写）+ #12（Feed 建模）+ #13（读）构成"高并发内容社区"的完整读写闭环，是简历主叙事。
