## 切片 #13：读路径加固（两级缓存 + 互斥锁防击穿 + 游标分页）

> Spec 全文：`docs/specs/read-path-hardening.md`（已落库）
> 架构决策：`docs/adr/0006-read-path-hardening.md`
> 术语：`CONTEXT.md`（缓存击穿 / 缓存穿透 / 两级缓存 / 游标分页 / 空值哨兵 / 内容字段）
> 承接：#11 写路径异步化（PR #70）、#12 Feed 流（PR #72）

### ① 难点叙事

#11 解决了互动**写**路径热点行竞争，#12 建了 Feed 流模型，但**读**路径仍是 #3 时期的"裸 Caffeine L1 + 全 offset 分页"，三个结构性矛盾（无生产流量，结构性论证）：

1. **缓存击穿**：热帖 `postDetail`（Caffeine，60s 物理过期）过期瞬间，N 个并发同时 miss → 全部穿透 DB 重建（post + author + media 三查询）→ 连接池/行锁打满。裸 `@Cacheable` 无重建保护，越热冲击越大。
2. **深分页退化**：所有列表走 `LIMIT offset, n`，DB 必须扫描并丢弃前 offset 行，O(offset+n)；第 1000 页要扫 1000×n 行。Feed `ZREVRANGE offset` 同理。
3. **单层缓存局限**：仅 Caffeine L1（进程内），多节点不共享、重启即全失、L1 miss 直打 DB，缺跨节点 L2 兜底。

**原方案为何不行**：裸 `@Cacheable` 注解无法表达"L1→L2→DB + 互斥重建 + 计数实时叠加"的复合逻辑；offset 分页的 `COUNT(*)` + 扫描丢弃是深分页慢的双重根因；单层 L1 在多节点/重启场景下缓存收益归零。

**额外矛盾**：#11 后互动计数权威实时源已是 Redis（`InteractionRedisStore`），而 `postDetail` 缓存嵌的是 DB 冗余列计数（异步落库滞后）——两级缓存若整个缓存含计数的详情，会与实时源打架、缓存到过期计数。

### ② 方案选型（选定项 + 拒绝理由，详见 Spec/ADR）

| 难点 | 选定方案 | 拒绝的候选 | 拒绝理由 |
|---|---|---|---|
| 缓存击穿 | **互斥锁重建**（复用 #5 `DistributedLock`，双重检查 + 自旋重读 + 降级直查） | 逻辑过期（异步重建返回旧值） | 详情含计数，返回 stale 误导用户；需额外重建线程池，复杂度高于收益 |
| 跨节点 L1 一致 | **Redis pub/sub 广播失效** + L1 短 TTL 兜底 | 仅 L1 短 TTL | 不一致窗口 = TTL（最长 10s）；pub/sub 是标准答案且复用现有 Redis |
| 缓存穿透 | **空值哨兵短 TTL（30s）** | 布隆过滤器 | 数据量小，布隆需维护全量位图 + 误判率 + 重建成本，过度设计 |
| 深分页 | **keyset 游标分页**（Feed `ZREVRANGEBYSCORE`、列表排序键元组比较，不透明 base64 cursor，不带 total） | offset 分页 / offset+cursor 并存 | offset 深翻页 O(offset)；并存则丢不掉 `COUNT(*)`，违背游标初衷 |
| 计数与缓存 | **详情只缓存内容字段，计数从 #11 实时 Redis 源叠加** | 缓存整个含计数的详情 | 计数会被缓存到过期值，与 #11 实时源冲突 |

### ③ 功能载体（只演示难点所需）

- **两级缓存组件**：L1 Caffeine（TTL 10s）→ L2 Redis（`cache:postDetail:{id}` JSON，TTL 60s）→ DB；`postDetail` 与 `publicProfile` 纳入。
- **互斥重建**：`lock:postDetail:{id}`（TTL 3s），双重检查，拿不到锁自旋 50ms×3 后降级直查。
- **pub/sub 失效**：写路径（update/delete/互动投影/评论增删）删 L2 + `PUBLISH cache:evict:postDetail`，各节点订阅清 L1。
- **空值哨兵**：查无结果缓存 `__NULL__` 短 TTL，命中直接抛 `ResourceNotFoundException`。
- **游标分页**：`CursorPageResult(items, nextCursor, hasMore)`（无 total）；Feed `readTimelineByCursor` + 帖子列表 latest/hot keyset 查询；cursor 与 offset 端点并存（以是否传 `cursor` 区分）。
- **前端**：Feed「关注」tab 改 cursor 无限滚动（"加载更多"透传 nextCursor）；列表页保留页码 UI 最小改动。

### ④ 验证叙事

- **测试 seam**（沿用既有，不新增基建）：service 级 `@SpringBootTest` + Testcontainers Redis + H2（prior art `CacheIntegrationTest`/`InteractionAsyncIntegrationTest`）验证击穿并发（DB 重建只 1 次）、两级缓存命中/回填、计数解耦、pub/sub 失效、空值哨兵；MockMvc（prior art `KurosBackendApplicationTests`）验证 cursor 端点契约 + offset 兼容。
- **性能验证（DoD 硬要求，禁止编造并发数字）**：击穿保护前后 DB 重建次数对比、offset page=1000 vs cursor 同深度耗时对比，实测数据写进 `docs/learning/13`；**耗时压测交用户执行**。
- **DoD 增量**：STAR 面试故事 + ≥5 条追问链 + 方案对比表 + 真实数据 + 七段式复盘。

### 范围边界（不做）

作者页/收藏/评论游标化、布隆过滤器、逻辑过期、热度衰减算法、取消关注清理 timeline、缓存雪崩专项（TTL 抖动）、全站前端无限滚动重写、commentCount 失联缺陷修复（#11 独立工单）。

### 关键追问点预埋

① 互斥锁 vs 逻辑过期（计数准确性）；② 详情缓存为何不存计数（与 #11 实时源解耦）；③ pub/sub 丢消息兜底（L1 短 TTL）；④ 游标分页为何不返回 total（省 `COUNT(*)`）；⑤ 同 score/同排序键翻页去重（id 兜底全序）；⑥ 击穿 vs 穿透 vs 雪崩区别。
