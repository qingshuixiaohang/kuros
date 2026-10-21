# MOD-01 举报处置回到内容领域写入口

状态：implemented（单元与编译验证通过，容器测试待 CI 复验）

依赖：无

规格 Issue：[#148](https://github.com/qingshuixiaohang/kuros/issues/148)

实现 Issue：[#147](https://github.com/qingshuixiaohang/kuros/issues/147)

规格：[`docs/specs/report-moderation-boundary.md`](../specs/report-moderation-boundary.md)

## 交付

- 举报创建通过帖子域、评论域公开校验入口检查目标可见性。
- 举报确认通过内容领域处置方法删除目标，不再直接写帖子或评论实体。
- 内容领域方法统一负责帖子详情缓存失效。
- 举报域不再依赖内容仓储、内容实体或 `TwoLevelCache`。

## TDD 验收

1. 添加架构边界失败测试，断言 `ContentReportService` 的字段与构造参数只允许依赖举报仓储及内容领域服务。
2. 确认测试因当前直接依赖帖子仓储、评论仓储和缓存而 RED。
3. 增加领域方法并迁移调用，确认架构测试 GREEN。
4. 增加举报确认委派测试及评论举报缓存失效集成用例。
5. 运行 `mvnw test-compile`、举报域单元测试和 `ReportDispositionCacheEvictionIntegrationTest`。
6. 回滚用例必须绕过两级缓存，直接通过帖子仓储断言数据库状态仍为 `PUBLISHED`。

## 已知限制

- 本工单不扩展内容恢复、媒体清理或 Feed 删除事件。

## 实现记录

- RED：边界测试 1 failure，观察到举报服务直接依赖帖子仓储、评论仓储和 `TwoLevelCache`。
- GREEN：`ContentReportServiceBoundaryTest` 5/5，覆盖精确依赖集合、帖子/评论确认委派、帖子/评论 404 错误码和行锁调用。
- 举报处理新增 `PESSIMISTIC_WRITE` 行锁，并发集成用例验证恰好一次成功。
- `mvnw test-compile` BUILD SUCCESS，编译 47 个测试源码。
- `ReportDispositionCacheEvictionIntegrationTest` 现有 7 个用例，覆盖帖子/评论缓存失效、提前删除幂等、
  举报保存失败整体回滚和并发确认；容器结果由 GitHub CI Docker runner 复验。
- `ContentReportLockIntegrationTest` 在 TDD 变异中 1/1 通过；移除 `@Lock` 时 RED，恢复后 GREEN。
