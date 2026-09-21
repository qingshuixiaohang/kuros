## Ticket 拆解（docs/tickets/）

| # | Ticket | 阻塞于 | 交付 |
|---|--------|--------|------|
| 1 | [split-01-shared-reorder](../blob/codex/issue-67-service-split/docs/tickets/split-01-shared-reorder.md) | 无 | shared 基础设施模块重排（纯移动，测试绿） |
| 2 | [split-02-user-reorder](../blob/codex/issue-67-service-split/docs/tickets/split-02-user-reorder.md) | split-01 | user 模块重排 + 内容模块引用更新 |
| 3 | [split-03-content-reorder-post-comment](../blob/codex/issue-67-service-split/docs/tickets/split-03-content-reorder-post-comment.md) | split-02 | post + comment 模块重排 |
| 4 | [split-04-content-reorder-rest](../blob/codex/issue-67-service-split/docs/tickets/split-04-content-reorder-rest.md) | split-03 | interaction + report + media 重排，Phase A 阶段验收 |
| 5 | [split-05-user-scaffold](../blob/codex/issue-67-service-split/docs/tickets/split-05-user-scaffold.md) | split-04 | kuros-user 工程骨架 + 独立库 + Nacos 注册 + compose/CI |
| 6 | [split-06-auth-migration](../blob/codex/issue-67-service-split/docs/tickets/split-06-auth-migration.md) | split-05 | 认证链路迁移 + 网关 /auth/** 路由 + user 测试 |
| 7 | [split-07-follow-migration](../blob/codex/issue-67-service-split/docs/tickets/split-07-follow-migration.md) | split-06 | 关注迁移 + 内部 API + V10 清理 + /users/*/follow 路由 |
| 8 | [split-08-backend-feign](../blob/codex/issue-67-service-split/docs/tickets/split-08-backend-feign.md) | split-07 | backend Feign 集成 + 组合视图 + 降级 + 双接缝测试 |
| 9 | [split-09-smoke-docs](../blob/codex/issue-67-service-split/docs/tickets/split-09-smoke-docs.md) | split-08 | compose/冒烟跨服务链路 + 学习复盘 + Issue 关闭 |

Spec 全文见仓库 `docs/slice10-service-split-spec.md`。按 frontier 顺序执行，split-01 可立即开始。

Phase A（1-4）为批次化重排：每批必须全量测试绿才进入下一批，Phase A 完成后单独 commit（可独立回退）；Phase B（5-9）第一个垂直切片从 split-06 起跑通业务端点。
