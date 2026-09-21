# 切片 #12 Ticket 拆解（Feed 流 ZSet Timeline）

**父 Issue**：待创建 · **Spec**：`docs/specs/feed-timeline.md` · **ADR**：`docs/adr/0005-feed-timeline-zset.md`

| # | Ticket | 阻塞于 | 交付 |
|---|--------|--------|------|
| 1 | [fd-01-timeline-store](./fd-01-timeline-store.md) | 无 | FeedTimelineStore（Redis ZSet 读写 + 容量裁剪 + TTL） |
| 2 | [fd-02-fanout](./fd-02-fanout.md) | fd-01 | PostPublishedEvent + @TransactionalEventListener 扇出（Feign 取粉丝 → ZADD） |
| 3 | [fd-03-feed-endpoint](./fd-03-feed-endpoint.md) | fd-01 | FeedService + FeedController（GET /api/v1/feed/following） |
| 4 | [fd-04-frontend](./fd-04-frontend.md) | fd-03 | 前端关注 tab 改调 /feed/following（推荐/最新 tab 不变） |
| 5 | [fd-05-tests](./fd-05-tests.md) | fd-03 | ZSet 操作测试 + 扇出集成测试 + 端点鉴权/分页测试 |
| 6 | [fd-06-docs](./fd-06-docs.md) | fd-05 | 学习复盘 + Issue 回写 + PR |

## 依赖图（DAG）

```
fd-01 ─┬─→ fd-02
       └─→ fd-03 ─→ fd-04 ─→ fd-05 ─→ fd-06
```

- **frontier**：fd-01 无阻塞，可立即开始。
- fd-02（扇出）依赖 fd-01（需要 ZSet store）；fd-03（读端点）也依赖 fd-01。
- fd-04（前端）依赖 fd-03（需要 API 存在）；fd-05（测试）依赖 fd-03 + fd-02；fd-06 收口。
