# 切片 #13 Ticket 拆解（读路径加固：两级缓存 + 互斥锁防击穿 + 游标分页）

**父 Issue**：[#73](https://github.com/qingshuixiaohang/kuros/issues/73) · **Spec**：`docs/specs/read-path-hardening.md` · **ADR**：`docs/adr/0006-read-path-hardening.md`

| # | Ticket | 阻塞于 | 交付 |
|---|--------|--------|------|
| 1 | [rp-01-two-level-cache](./rp-01-two-level-cache.md) | 无 | 两级缓存读组件（L1 Caffeine + L2 Redis JSON）+ postDetail/publicProfile 走两级读 + 计数解耦（内容缓存、计数实时叠加） |
| 2 | [rp-02-breakdown-mutex](./rp-02-breakdown-mutex.md) | rp-01 | 互斥锁防击穿（双重检查 + 自旋重读 + 降级直查）+ 空值哨兵防穿透 |
| 3 | [rp-03-pubsub-evict](./rp-03-pubsub-evict.md) | rp-01 | 写路径删 L2 + pub/sub 广播失效各节点 L1 + 短 TTL 兜底 |
| 4 | [rp-04-cursor-feed](./rp-04-cursor-feed.md) | 无 | CursorPageResult 契约 + base64 cursor 编解码 + Feed ZREVRANGEBYSCORE 游标读 + Feed cursor 端点 |
| 5 | [rp-05-cursor-postlist](./rp-05-cursor-postlist.md) | rp-04 | 帖子列表 keyset 查询（latest/hot）+ findPublishedByCursor + /posts cursor 端点（与 offset 并存） |
| 6 | [rp-06-frontend-infinite](./rp-06-frontend-infinite.md) | rp-04 | 前端 Feed 关注 tab cursor 无限滚动（加载更多透传 nextCursor） |
| 7 | [rp-07-tests-perf](./rp-07-tests-perf.md) | rp-01~06 | 击穿并发/两级缓存/计数解耦/pub-sub/哨兵/cursor 正确性 + HTTP 契约测试 + offset vs cursor 性能对比数据 |
| 8 | [rp-08-docs](./rp-08-docs.md) | rp-07 | docs/learning/13 七段式复盘 + Issue #73 回写 + PR |

## 依赖图（DAG）

```
缓存轨：rp-01 ─┬─→ rp-02
              └─→ rp-03
游标轨：rp-04 ─┬─→ rp-05
              └─→ rp-06
汇合：  rp-01 rp-02 rp-03 rp-04 rp-05 rp-06 ─→ rp-07 ─→ rp-08
```

- **frontier（可立即并行起跑）**：rp-01（缓存轨根）、rp-04（游标轨根）无阻塞。
- rp-02/rp-03 阻塞于 rp-01（需两级读组件就位）；rp-05/rp-06 阻塞于 rp-04（需 CursorPageResult + cursor 编解码）。
- rp-07 阻塞于全部实现 ticket（跨切面集成测试 + 性能验证）；rp-08 收口。

## 执行说明

- 每个 ticket 是一个可独立验收的垂直切片，按 frontier 顺序执行；缓存轨与游标轨可并行。
- **TDD**：每个 ticket 先写失败测试再实现最小行为；rp-07 做跨切面集成测试 + 性能验证收口。
- **耗时测试纪律**：全量 `mvn test`、Testcontainers 集成、compose 冒烟、性能压测**交用户/CI**；AI 只做编译级快验（`test-compile` / `compose config` / `node --check`）。
- **收益表述纪律**：只用可验证结构性指标 + 实测数据，不编造 TPS/RT 并发数字（CONTEXT.md 方向纠偏）。
- **关键架构不变量**（rp-01 起全程遵守）：postDetail 缓存只存内容字段，互动计数永远从 #11 实时 Redis 源叠加，不进缓存。
