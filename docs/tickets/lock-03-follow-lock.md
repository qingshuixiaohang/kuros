# Ticket: UserFollowService 加锁

**依赖**：ticket-01
**阻塞**：ticket-03

## 范围

1. `UserFollowService.follow()` 加分布式锁 `lock:follow:{userId}:{targetId}`
2. 获取不到锁时快速失败（返回当前状态）
3. try-finally 确保 unlock

## 验收

- 并发关注不会产生重复记录
- 全量测试通过
