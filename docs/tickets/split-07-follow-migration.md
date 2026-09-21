# Ticket: 关注迁移 + 内部 API + 数据清理（Phase B-3）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：split-06
**阻塞**：split-08

## 范围

1. 关注代码迁入 kuros-user：`UserFollow` 实体/仓储、`UserFollowService`（保留分布式锁防重复关注）、`UserFollowController`（GET/POST/DELETE `/api/v1/users/{targetUserId}/follow`）
2. kuros-user 内部 API（不经网关）：批量用户查询（ID 列表 → `{id, nickname, avatarUrl}`）、关注状态/计数查询；注释标注"生产需 mTLS 或内部 token"
3. backend：`V10` 迁移 DROP 迁走的表（community_user、sys_*、user_follow、user_sessions）；删除 `UserSession`/`UserSessionRepository` 死代码；移除关注相关代码
4. Gateway 路由：`/api/v1/users/*/follow` → `lb://kuros-user`
5. 测试：kuros-user 侧关注行为测试（含并发防重复）；backend 侧迁移后全量测试绿

## 验收

- [x] 关注操作经网关由 kuros-user 提供，并发防重复行为保持（静态+编译级闭环：网关 `kuros-user-follow` 路由 `order(-1)` 显式优先于 backend `/**`，双桩集成测试证命中；kuros-user 关注集成测试含 16 线程并发防重复、幂等与 401/400/404 断言；端到端运行时验证待 CI 冒烟）
- [x] 内部 API 可返回批量用户与关注信息（kuros-user 集成测试覆盖 `/internal/v1/users`：batch 简档、follow-stats、following/followers 分页）
- [x] backend 库中用户域表已 DROP（V10），死代码已删（V10 先摘 7 个 FK 再 DROP 7 张表，MySQL 8 / H2 双兼容；16 个用户域文件删除，编译零残留引用；Flyway 实际执行待 CI）
- [ ] 两侧测试全绿；CI 绿（待触发）

## 备注

- 帖子 `author_id` 裸引用保留在内容库——跨库无 FK 从本 ticket 起成为现实（一致性靠约定 ID）
- 窗口期降级升级：资料/个人中心整端 503 `SERVICE_UNAVAILABLE`（不用 404——误导"用户不存在"；不用 500——计划内可恢复）；帖子列表仍可用（内容本库自持），作者占位"未知漂泊者"且保留 `authorId`——前端关注按钮以 `author.id` 为目标标识，窗口期关注链路可用
- 关注链路的回滚安全：锁 key（`lock:follow:` + 同 TTL）逐字保留——迁移期若两侧短暂共存，同一对关系仍互斥
- 迁移时两处"看起来该删、实际不能删"：`DistributedLock`（PostInteractionService 点赞/收藏仍在用）、`ProfileCommentResponse`（被 ProfileOverviewResponse 引用）
- code-review 发现（仅注释修正，行为不动）：`UserFollowService` 类级 `@Transactional` 使实际时序为 tx begin → lock → … → unlock → commit（TransactionTemplate 以 REQUIRED 加入外层）——"锁包裹事务"表述与实现不符，防重实为三道防线（锁串行化 + 锁内 existsById + 复合主键兜底）；"unlock 在 commit 之后"的增强留后续切片评估（需连同并发测试）
- 脚本适配：smoke 增 follow 冒烟（经网关断言 followed=true，幂等可重复）；会话探针自 backend 的 me/profile（现 503）迁到 kuros-user 自有 `/api/v1/auth/me`，check 探活直连 8091（8080 是网关 health，不反映 user 就绪）
