# Ticket: PostInteractionService 加锁

**依赖**：ticket-01
**阻塞**：ticket-03

## 范围

1. `PostInteractionService.like()` 加分布式锁 `lock:like:{userId}:{postId}`
2. `PostInteractionService.favorite()` 加分布式锁 `lock:favorite:{userId}:{postId}`
3. 获取不到锁时快速失败（返回当前状态），保证幂等性
4. try-finally 确保 unlock

## 验收

- 并发点赞不会产生重复记录
- 全量测试通过
