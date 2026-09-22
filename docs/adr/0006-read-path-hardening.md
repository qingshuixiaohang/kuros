# 0006. 读路径加固：两级缓存 + 互斥锁防击穿 + 游标分页

日期：2026-09-22

状态：已接受

## 背景与问题

#11 把互动**写**路径异步化（Redis 计数前置 + RocketMQ 顺序落库），#12 建了 Feed 流（Redis ZSet Timeline）。但**读**路径仍是 #3 时期的裸 Caffeine L1 + 全 offset 分页，暴露三个结构性矛盾（本项目无生产流量，为结构性论证，非事故复盘）：

1. **缓存击穿**：热帖 `postDetail`（Caffeine，60s 物理过期）过期瞬间，N 个并发请求同时 miss → 全部穿透 DB 重建（post + author + media 三次查询）→ 连接池/行锁被打满。裸 `@Cacheable` 无任何重建保护，越热的帖子过期时冲击越大。
2. **深分页退化**：所有列表走 offset 分页（`PageRequest.of(page-1, size)` → `LIMIT offset, n`）。DB 执行 `LIMIT offset, n` 必须扫描并丢弃前 offset 行，复杂度 O(offset+n)；翻到第 1000 页要扫 1000×n 行。Feed 的 `ZREVRANGE offset, offset+limit-1` 同理，offset 越大越慢。
3. **单层缓存的局限**：仅 Caffeine L1（进程内），多节点不共享、服务重启即全部失效、L1 miss 直接打 DB。缺少跨节点共享的 L2 兜底层。

**为什么此刻做**：#11/#12 解决了写与 Feed 建模，读路径是"高并发内容社区"叙事的最后一块——热帖读、Feed 读是社区最高频入口。把读路径抗住高并发，与 #11 写路径形成完整闭环，是面试可深挖的核心载体。

## 决策

读路径加固为 **两级缓存（L1 Caffeine + L2 Redis）+ 互斥锁防击穿 + 游标分页**，三者咬合：

1. **两级缓存读流**：L1 Caffeine（纳秒，进程内）→ L2 Redis（毫秒，跨节点共享）→ DB（互斥重建）。L1 命中直接返回；L1 miss 查 L2，命中则回填 L1 返回；L2 miss 才走 DB 重建并回填 L2 + L1。L1 短 TTL（10s）、L2 长 TTL（60s），L1 短 TTL 限制跨节点不一致窗口。
2. **互斥锁防击穿**：L2 miss → DB 重建前，用 #5 的 `DistributedLock`（SET NX EX + Lua 释放）抢 `lock:postDetail:{postId}`（每帖独立锁，不全局串行）。**双重检查**：拿到锁后先重读 L2（可能已被其他线程重建）再查 DB。拿不到锁的线程短暂自旋重读 L2（如 50ms×3）；仍 miss 则**降级直查 DB**（不无限等待，Redis 故障不阻断读）。锁 TTL 短（3s）防死锁。
3. **跨节点 L1 一致性（pub/sub 广播失效）**：写路径（帖子 update/delete、互动投影落库、评论增删）→ 删 L2 + `PUBLISH cache:evict:postDetail {postId}` → 各节点订阅后清本地 L1。L1 仍保留短 TTL 作 pub/sub 丢消息的兜底。复用现有 Redis，无需新中间件。
4. **⭐缓存与实时计数解耦**：`postDetail` 两级缓存**只缓存内容字段**（标题/正文/作者/媒体/分类/标签/发布时间），**不缓存易变的互动计数**。计数在组装 `PostDetailResponse` 时从 #11 的实时 Redis 读源（`InteractionRedisStore.readCount`）叠加最新值。避免"两级缓存缓存了过期 DB 列计数"与"#11 实时计数源"打架——这是本切片最关键的架构约束。
5. **缓存穿透防护**：不存在的 postId 反复查会每次穿到 DB。对查无结果缓存**空值哨兵**（短 TTL 30s），命中哨兵直接抛 `ResourceNotFoundException`，不打 DB。
6. **游标分页（keyset）**：
   - **Feed 关注流**：cursor = (score, postId)，用 `ZREVRANGEBYSCORE key (cursorScore -inf LIMIT 0 n` 替代 `ZREVRANGE offset`，复杂度 O(log N + n) 与翻页深度无关；同 score 用 postId 兜底去重。
   - **帖子列表 latest/hot**：keyset 谓词。latest 排序键 (published_at, id)，hot 排序键 (like_count, comment_count, published_at, id)；`WHERE (排序键元组) < (cursor 元组) ORDER BY 排序键 DESC LIMIT n`，id 兜底保证稳定全序、翻页不丢不重。
   - **cursor 编码**：不透明 base64（内部含排序值 + id），对外单 `cursor` 参数 + `limit`；前端只透传 `nextCursor`。
   - **响应契约**：新增 `CursorPageResult<T>(items, nextCursor, hasMore)`，**不含 totalItems/totalPages**（游标分页不执行 `COUNT(*)`，省掉全表计数正是性能收益点）。旧 offset 端点（作者页/收藏/评论）保留 `PageResult` 不破坏。
7. **publicProfile 纳入 L2**：`findPublic` 经 Feign 跨服务读 kuros-user，L2 缓存省一次网络往返，收益比 L1 更大；失效仍靠 TTL（kuros-user 改资料无事件通道，沿用 #3 的最终一致约束）。
8. **前端**：Feed「关注」tab 改 cursor 无限滚动（"加载更多"透传 nextCursor）；攻略/帖子列表页后端 cursor 就绪，前端保留页码 UI 用 cursor 模拟"下一页"，最小改动。

## 备选方案

- **逻辑过期防击穿（替代互斥锁）**：缓存不物理过期，value 内嵌逻辑过期时间，过期后异步重建、期间返回旧值。优点：读永不阻塞。缺点：返回 stale 数据——`postDetail` 含计数/内容，返回旧值误导用户；且需额外异步重建线程池与"谁触发重建"的协调，复杂度高于收益。拒。
- **仅 L1 短 TTL 保跨节点一致（替代 pub/sub）**：实现最简，但不一致窗口 = L1 TTL，热点写后各节点最长 10s 才收敛。pub/sub 是两级缓存一致性的标准答案且复用现有 Redis，故采用 pub/sub + 短 TTL 兜底双保险。
- **布隆过滤器防穿透（替代空值哨兵）**：能拦截"绝对不存在"的 id，但需维护全量 postId 的位图、新增帖子要同步进过滤器、有误判率与重建成本。本项目数据量小，空值哨兵短 TTL 足够。仅进方案对比不实现。
- **游标分页覆盖全部列表**：作者页/收藏/评论也游标化。但这些非高并发热点，且收藏/评论的排序键游标化收益小、改动面大，稀释主叙事。本片只做 Feed + 帖子列表两个热点。拒（留下一片）。
- **offset 与 cursor 并存于同一响应**：既返回 page/totalPages 又返回 nextCursor。`COUNT(*)` 是深分页慢的另一半根因，保留 total 就丢不掉全表计数，违背游标分页初衷。故 cursor 端点坚决不带 total。
- **缓存整个含计数的 PostDetailResponse**：实现最直观，但计数会被缓存到过期值，与 #11 实时 Redis 计数源冲突（用户点赞后详情仍显示旧计数直到缓存驱逐）。故决策 4 强制"内容缓存 + 计数实时叠加"分离。拒。

## 影响

- **前端**：Feed 关注 tab 改无限滚动（新增"加载更多" + nextCursor 透传）；帖子列表页 UI 基本不变（后端 cursor 就绪，前端用 cursor 模拟翻页）。详情页计数改由实时源叠加，语义与 #11 一致（Redis 实时值）。
- **API 契约**：新增 cursor 分页端点/参数（`cursor` + `limit`，返回 `CursorPageResult`）；旧 offset 端点（`page`/`pageSize` → `PageResult`）保留兼容，不破坏现有调用方。
- **缓存架构**：从单层 Caffeine 升级为 L1 Caffeine + L2 Redis 两级；新增 pub/sub 失效广播通道（`cache:evict:postDetail`）+ 空值哨兵 + 互斥重建锁（`lock:postDetail:{id}`）。Redis 承载 L2 详情缓存（内存 + key 治理 + JSON 序列化）。
- **一致性模型**：postDetail 内容字段跨节点准实时一致（pub/sub 失效）+ L1 短 TTL 兜底；计数始终走 #11 实时源不进缓存。publicProfile 仍 TTL 最终一致。
- **测试**：新增击穿并发测试（N 线程打同一过期热帖，断言 DB 重建只发生一次）、游标分页测试（深翻页正确性 + 同分/同排序键去重 + 不丢不重）、两级缓存测试（L1/L2 命中与回填、pub/sub 跨节点失效、空值哨兵防穿透、计数实时叠加不被缓存）。
- **运维面**：Redis 新增 L2 缓存 key 空间 + pub/sub 通道；互斥锁 key 治理。
- **已知限制**：pub/sub 不保证送达（Redis 断连期间失效消息丢失）→ L1 短 TTL 兜底，最长 10s 收敛；空值哨兵期间"先查无后被创建"的帖子最长 30s 不可见（社区场景可接受）；游标分页不支持跳页（只能"下一页"），符合 feed/列表的产品语义。
- **收益验证方式（可复现，禁止编造并发数字）**：① 击穿——并发测试对比"无保护 vs 互斥锁"下同一过期热帖的 DB 重建次数（期望从 N 次降到 1 次）；② 深分页——offset page=1000 vs cursor 同深度的查询耗时/P95 对比（真实测量）；③ 两级缓存——L1/L2 命中率 + pub/sub 失效延迟。性能类数字以实测为准，无压测数据则只做结构性论证。

## 对应小哈书章节

- 缓存击穿（互斥锁 / 逻辑过期）、缓存穿透（空值缓存 / 布隆过滤器）、两级缓存（Caffeine + Redis）与一致性（pub/sub 广播失效）；深分页优化（keyset / 游标分页替代 offset）。

## 简历产出

> 为高并发内容社区读路径做系统性加固：① 用互斥锁（Redis SET NX EX + Lua 释放）+ 双重检查重建解决热帖缓存击穿，把过期瞬间 N 个并发穿透 DB 收敛为 1 次重建，拿不到锁降级直查保证 Redis 故障不阻断读；② 建 L1 Caffeine + L2 Redis 两级缓存，L2 跨节点共享、重启不失效，写路径经 Redis pub/sub 广播失效各节点 L1 保证准实时一致；③ 关键架构约束——详情缓存只存内容字段，易变互动计数从实时 Redis 源叠加，避免两级缓存与异步计数源打架；④ 用 keyset 游标分页（Feed 走 ZREVRANGEBYSCORE、列表走排序键元组比较 + 不透明 base64 cursor）替代 offset 深分页，去掉 COUNT(*) 全表计数，翻页耗时与深度解耦；⑤ 空值哨兵短 TTL 防缓存穿透。
