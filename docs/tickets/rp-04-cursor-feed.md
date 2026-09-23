# rp-04: 游标分页基建 + Feed 关注流

**What to build:** 关注流"加载更多"用游标翻页——以上一页最后一条的 (score, postId) 为游标，用 `ZREVRANGEBYSCORE` 取下一页，无论翻到多深加载速度都不变慢；响应返回 `nextCursor` + `hasMore`，前端只透传游标。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] 新增 `CursorPageResult<T>(List<T> items, String nextCursor, boolean hasMore)` 契约（不含 totalItems/totalPages，不执行 `COUNT(*)`）
- [x] 新增不透明 base64 cursor 编解码工具 `CursorCodec`（token 用 U+001F 连接后 base64url；非法游标抛 INVALID_CURSOR）
- [x] `FeedTimelineStore.readTimelineByCursor(userId, cursorScore, cursorPostId, limit)`：`ZREVRANGEBYSCORE`（闭区间 max + 多取一 limit 缓冲），同分用 postId 字典序兜底跳过已返回的边界成员
- [x] `FeedService.findFollowingFeedByCursor(userId, cursor, limit)` 返回 `CursorPageResult`，委托 `CommunityPostService.findPublishedByIds` 批量回填（nextCursor 基于 timeline 原始 id 序列，不因已删帖错位）
- [x] `FeedController` 扩展 `GET /api/v1/feed/following?cursor={c}&limit={n}`（以是否传 `limit` 区分游标/offset 模式，两套契约并存）
- [x] 测试（TDD 先行）：游标遍历不丢不重且顺序 == 全量倒序、同分去重、hasMore/nextCursor 语义、空页、非法游标（FeedCursorIntegrationTest 5 测试全绿）
- [x] 编译级快验通过；现有 Feed 测试不破坏（FeedTimelineIntegrationTest 8 测试全绿）
