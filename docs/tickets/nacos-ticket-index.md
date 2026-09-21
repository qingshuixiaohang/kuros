## Ticket 拆解（docs/tickets/）

| # | Ticket | 阻塞于 | 交付 |
|---|--------|--------|------|
| 1 | [nacos-01-discovery](../blob/codex/distributed-session-auth/docs/tickets/nacos-01-discovery.md) | 无 | 依赖引入 + compose nacos 服务 + 注册集成测试 |
| 2 | [nacos-02-config-refresh](../blob/codex/distributed-session-auth/docs/tickets/nacos-02-config-refresh.md) | nacos-01 | 分层配置 + @RefreshScope 动态刷新 + 覆盖/刷新测试 |
| 3 | [nacos-03-smoke-docs](../blob/codex/distributed-session-auth/docs/tickets/nacos-03-smoke-docs.md) | nacos-02 | 冒烟扩展 + 学习复盘 + 关闭本 Issue |

Spec 全文见仓库 `docs/slice8-nacos-spec.md`。按 frontier 顺序执行，nacos-01 可立即开始。
