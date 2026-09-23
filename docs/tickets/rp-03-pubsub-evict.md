# rp-03: pub/sub 跨节点 L1 失效

**What to build:** 帖子被编辑/删除、互动投影落库、评论增删后，所有节点的本地 L1 缓存对应键被准实时清除，多节点部署下不会读到旧内容；pub/sub 丢消息时由 L1 短 TTL 兜底收敛。

**Blocked by:** rp-01（需两级缓存读组件 + L1 就位）

**Status:** done

- [x] 写路径（帖子 update/delete、评论 create/delete）→ 删 L2 key + `PUBLISH cache:evict:postDetail {postId}`（已统一收敛到 `TwoLevelCache.evict`）
- [x] 各节点启动订阅 `cache:evict:*` 频道（`RedisMessageListenerContainer` + `CacheEvictionListener`），收到消息清本地 L1 对应键
- [x] 现有 `@CacheEvict("postDetail")` 注解已于 rp-01 全部迁走，统一走“删 L2 + publish”失效路径
- [x] L1 短 TTL（10s）作为 pub/sub 丢消息兜底，文档注明最长 10s 收敛
- [x] 测试（TDD 先行）：写路径触发后断言 L2 被删 + 失效消息发出（探针订阅者捕获）；直接向频道发消息模拟另一节点，验证生产监听器收到后清本地 L1
- [x] 编译级快验通过；现有测试不破坏

> **实现偏差记录（与 D4 字面不同）**：spec D4 将 `InteractionProjectionService.apply` 也列为驱逐触发点，但 rp-01 已将计数与内容缓存解耦（D3）——postDetail 只缓存内容字段，互动投影只改计数列、不动内容，故 apply **不再驱逐详情缓存**（热帖每次互动落库不再击穿内容缓存，反而是收益）。计数实时性由 #11 `InteractionRedisStore` 保证。
