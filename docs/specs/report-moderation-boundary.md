# 举报处置的领域写边界

## 目标

管理员确认内容举报时，由帖子域或评论域执行内容处置，举报域只负责举报记录和审核流程。消除 `report` 包直接修改帖子、评论实体并手工驱逐缓存的分层穿透。

本切片解决后端审计 M14 的剩余部分。`post` 与 `interaction` 的循环依赖已由独立切片拆开，本次只处理举报处置绕过内容领域服务的问题。

## 方案选择

| 方案 | 判断 |
| --- | --- |
| 举报域继续直接操作内容仓储和实体 | 拒绝。每个新增内容规则都要在作者写路径和举报写路径各实现一次，容易出现缓存、事件和媒体规则漂移 |
| 举报域调用帖子域、评论域公开的处置方法 | 采用。举报仍拥有审核状态；内容状态变化回到唯一领域入口，边界清楚且不引入共享模块 |
| 建立内容处置公共模块供三个领域调用 | 拒绝。当前只有两个调用方，抽公共模块会增加依赖和版本同步成本，尚无第三次复用证据 |

## 领域契约

- `PostPublishingService.ensurePublished(postId)` 校验帖子当前可被举报；不存在或未发布时返回 `POST_NOT_FOUND`。
- `PostPublishingService.deleteForModeration(postId)` 将已发布帖子置为删除态并登记帖子详情缓存失效；目标已删除或不存在时保持幂等，不新增对外错误。
- `CommunityCommentService.ensureReportable(commentId)` 校验评论当前可被举报；不存在或已删除时返回 `COMMENT_NOT_FOUND`。
- `CommunityCommentService.deleteForModeration(commentId)` 将正常评论置为删除态并登记所属帖子的详情缓存失效；目标已删除或不存在时保持幂等。
- 举报域不得直接依赖 `CommunityPostRepository`、`CommunityCommentRepository`、帖子或评论实体以及帖子详情缓存。
- 缓存失效继续通过 `CacheInvalidationAfterCommit` 延迟到事务提交后。

## 不变量

1. 举报创建时对目标可见性的校验和 404 错误码不变。
2. 举报驳回不改变帖子、评论或其缓存。
3. 举报确认后，目标内容在事务提交后不可再作为正常内容读取。
4. 举报记录与内容处置处于同一事务；任一步失败时整体回滚。
5. 同一待处理举报只能被处理一次；目标在举报后、确认前被作者删除时，确认仍可完成且不产生 500。

## 验收标准

1. 新增失败测试证明 `ContentReportService` 不直接依赖内容仓储、内容实体和帖子详情缓存。
2. 单元契约测试证明确认帖子举报调用 `PostPublishingService.deleteForModeration`，确认评论举报调用 `CommunityCommentService.deleteForModeration`。
3. 现有 `ReportDispositionCacheEvictionIntegrationTest` 保持通过，帖子确认后 L1、L2 和 Redis 失效广播均被验证。
4. 新增评论确认用例，验证所属帖子详情缓存失效且原评论处于已删除状态。
5. `mvnw test-compile`、新增单元测试和相关定向 Testcontainers 集成测试通过，CI 在干净 runner 上复验。

## 非目标

- 不改变管理员接口、举报 DTO 或错误码。
- 不增加内容恢复、审核记录历史或后台 CRUD。
- 不新增帖子或评论删除事件；只有现有领域行为存在时才复用。
- 不处理媒体清理、Feed 删除事件或搜索索引策略。
