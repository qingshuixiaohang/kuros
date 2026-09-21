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

- [ ] 关注操作经网关由 kuros-user 提供，并发防重复行为保持
- [ ] 内部 API 可返回批量用户与关注信息
- [ ] backend 库中用户域表已 DROP（V10），死代码已删
- [ ] 两侧测试全绿；CI 绿

## 备注

- 帖子 `author_id` 裸引用保留在内容库——跨库无 FK 从本 ticket 起成为现实（一致性靠约定 ID）
