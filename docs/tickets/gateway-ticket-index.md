## Ticket 拆解（docs/tickets/）

| # | Ticket | 阻塞于 | 交付 |
|---|--------|--------|------|
| 1 | [gateway-01-scaffold-nacos](../blob/codex/distributed-session-auth/docs/tickets/gateway-01-scaffold-nacos.md) | 无 | kuros-gateway 工程 + compose 集成（8080 正门 / 8090 调试口）+ 注册集成测试 |
| 2 | [gateway-02-routing](../blob/codex/distributed-session-auth/docs/tickets/gateway-02-routing.md) | gateway-01 | lb://kuros-backend 显式路由 + 桩服务器转发测试 + lb 服务发现测试 |
| 3 | [gateway-03-smoke-docs](../blob/codex/distributed-session-auth/docs/tickets/gateway-03-smoke-docs.md) | gateway-02 | 冒烟扩展 + 学习复盘 + 关闭 Issue #65 |

Spec 全文见仓库 `docs/slice9-gateway-spec.md`。按 frontier 顺序执行，gateway-01 可立即开始。
