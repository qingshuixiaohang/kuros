# rp-01: 两级缓存读路径贯通 + 计数解耦

**What to build:** 帖子详情/公开资料的读请求依次穿过 L1 Caffeine → L2 Redis → DB，命中即返回；详情缓存只存内容字段，点赞/收藏计数在组装响应时从 #11 实时 Redis 源叠加最新值。服务重启后热帖详情仍能从 L2 命中（跨重启共享），且用户看到的计数永远是实时的。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 新增两级缓存读组件（L1 Caffeine TTL 10s + L2 Redis `cache:postDetail:{id}` JSON TTL 60s），封装 L1→L2→DB 读流与回填
- [ ] `CommunityPostService.findPublishedById` 从 `@Cacheable` 注解式单层缓存改为走两级缓存组件
- [ ] **计数解耦**：缓存的详情只含内容字段（标题/正文/摘要/作者/媒体/分类/标签/发布时间/浏览数），`likeCount`/`favoriteCount` 在组装 `PostDetailResponse` 时从 `InteractionRedisStore.readCount` 叠加
- [ ] `publicProfile` 纳入 L2（省 Feign 往返），失效靠 TTL
- [ ] `CacheConfig` L1 TTL 调整为 10s（限制跨节点不一致窗口），保留 `spring.cache.type=none` 测试开关
- [ ] 单元测试（TDD 先行）：L1 miss→L2 hit→回填 L1；L2 miss→DB→回填 L2+L1；缓存内容后改 Redis 实时计数，断言详情返回最新计数（缓存未污染计数）
- [ ] 编译级快验通过（`mvnw test-compile`）；现有测试不破坏
