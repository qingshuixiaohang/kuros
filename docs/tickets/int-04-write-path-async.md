# Ticket: 写路径改造——Redis 前置 + 发顺序消息 + 同步降级（切片 #11 · B-2）

**父 Issue**：#69（切片 #11：互动写路径异步化）
**依赖**：int-02、int-03
**阻塞**：int-05

## 范围

1. 改造 `PostInteractionService.like/favorite/unlike/unfavorite`：保留 per-user 分布式锁 `lock:{type}:{postId}:{userId}`（抢锁失败返回 Redis 快照，幂等），**锁内不再同步 `UPDATE posts`**：
   a. 校验帖 PUBLISHED（读，走缓存/DB，不争写行锁）
   b. 回填保障：计数/状态 key miss 时从 DB 回填（调 int-02 的读源组件）
   c. 翻转判定 + SET 新状态 + 条件 INCR/DECR 计数（int-02 提供）
   d. 发 `InteractionEvent` 到 `post-interaction-topic`，顺序消息、分区键 = postId（`StreamBridge`）
   e. **发送失败 → 同步降级**：锁内 `TransactionTemplate` 执行原同步写路径（`existsById` + save/delete 关系行 + increment/decrementCount），保证互动不丢
   f. 返回 `PostInteractionResponse`（取自 Redis 实时读源）
2. `@CacheEvict postDetail` 语义复核：互动快照已走 Redis，postDetail 缓存的详情计数保持读 DB 冗余列（消费端刷，最终一致）——按 Spec 3.5 范围收敛处理
3. 测试（TDD 先 RED）：
   - 点赞后 HTTP 响应即时 `liked=true` 且 `likeCount`=基线+1（Redis 实时）
   - 重复点赞幂等（Redis 计数只 +1）；取消对称
   - **降级路径**：spy/mock `StreamBridge` 发送抛异常 → DB 关系 + 计数同步落库、不丢、响应正常

## 验收

- [ ] 四个写方法请求线程不再同步 `UPDATE posts`（计数经 Redis 前置 + 消费端落库）
- [ ] 点赞/收藏/取消 HTTP 响应即时返回 Redis 实时值；契约字段不变
- [ ] 发送顺序消息（分区键 postId）成功路径打通
- [ ] MQ 发送失败时同步降级落库、互动不丢（降级测试绿）
- [ ] 重复点击幂等（Redis 计数不重复累加）

## 备注

- 这是难点兑现的核心切片：跨用户对热点帖计数行的 DB 行锁竞争 → 收敛到消费端单队列顺序写
- 降级路径复用 int-03 的落库逻辑或原写路径；确保 Redis 已前置，降级只补 DB
