# Ticket: Redis 实时读源 + 互动快照读路径 Redis 优先（切片 #11 · A-2）

**父 Issue**：#69（切片 #11：互动写路径异步化）
**依赖**：无（可立即开始，与 int-01 并行）
**阻塞**：int-04

## 范围

1. 新增互动 Redis 读源组件（如 `InteractionRedisStore`），封装四类 key：
   - 状态 `interaction:like:{postId}:{userId}` / `interaction:favorite:{postId}:{userId}`（String `"1"`/`"0"`）
   - 计数 `interaction:count:like:{postId}` / `interaction:count:favorite:{postId}`（String 整数）
2. **Lua 原子回填计数**：`if EXISTS==0 then SET 基线; INCRBY 增量`，基线由应用层先从 DB `posts.{like,favorite}_count` 读入并作为参数传入，杜绝并发 miss 重复回填
3. **状态回填**：miss 时从 DB `post_like/post_favorite.existsById` 回填 `"1"`/`"0"` + TTL（单 key 幂等无竞态）
4. **翻转计数**：提供「读旧状态 → SET 新状态 → 仅当翻转时 INCR/DECR」的原子/受保护操作，供写路径（int-04）调用
5. **读路径改造**：`PostInteractionService.findPost` 改为 Redis 优先——`liked/favorited` 与 `likeCount/favoriteCount` 从 Redis 读，miss 回源 DB 并回填；`PostInteractionResponse` 契约不变
6. 测试（TDD 先 RED）：Redis 命中读、miss 回源 DB 回填、计数回填 Lua 原子性、翻转判定（复用 Testcontainers Redis）

## 验收

- [ ] findPost 走 Redis 优先，miss 回源 DB 冗余列/existsById 并回填；HTTP 契约字段不变
- [ ] 计数回填 Lua 在并发 miss 下基线只设一次（单测覆盖）
- [ ] 翻转判定：重复同向操作不重复 INCR/DECR（单测覆盖）
- [ ] Redis 读源组件测试全绿（Testcontainers Redis）

## 备注

- 本 ticket 只做 Redis 读源 + 读路径，不改写路径的 DB 落库（写路径在 int-04）
- 帖子详情/列表计数展示**不在本切片**（保持读 DB 冗余列，Redis 优先留 #13）
- 新增缓存若走 Caffeine `@Cacheable` 须在 `CacheConfig` 登记缓存名（静态模式未登记会静默失效）；本 ticket 用 StringRedisTemplate 直管 key，不新增 Caffeine 缓存名
