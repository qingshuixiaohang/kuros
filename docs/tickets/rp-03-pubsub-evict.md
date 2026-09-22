# rp-03: pub/sub 跨节点 L1 失效

**What to build:** 帖子被编辑/删除、互动投影落库、评论增删后，所有节点的本地 L1 缓存对应键被准实时清除，多节点部署下不会读到旧内容；pub/sub 丢消息时由 L1 短 TTL 兜底收敛。

**Blocked by:** rp-01（需两级缓存读组件 + L1 就位）

**Status:** ready-for-agent

- [ ] 写路径（帖子 update/delete、`InteractionProjectionService.apply`、评论 create/delete）→ 删 L2 key + `PUBLISH cache:evict:postDetail {postId}`
- [ ] 各节点启动订阅 `cache:evict:postDetail` 频道（`RedisMessageListenerContainer`），收到消息清本地 L1 对应键
- [ ] 移除/收敛现有 `@CacheEvict("postDetail")` 注解，统一走"删 L2 + publish"失效路径（避免注解式单层驱逐与两级缓存并存冲突）
- [ ] L1 短 TTL（10s）作为 pub/sub 丢消息兜底，文档注明最长 10s 收敛
- [ ] 测试（TDD 先行）：写路径触发后断言 L2 被删 + 失效消息发出；用第二个缓存实例/订阅者验证收到消息后 L1 被清
- [ ] 编译级快验通过；现有测试不破坏
