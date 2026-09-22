# rp-04: 游标分页基建 + Feed 关注流

**What to build:** 关注流"加载更多"用游标翻页——以上一页最后一条的 (score, postId) 为游标，用 `ZREVRANGEBYSCORE` 取下一页，无论翻到多深加载速度都不变慢；响应返回 `nextCursor` + `hasMore`，前端只透传游标。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 新增 `CursorPageResult<T>(List<T> items, String nextCursor, boolean hasMore)` 契约（不含 totalItems/totalPages，不执行 `COUNT(*)`）
- [ ] 新增不透明 base64 cursor 编解码工具（内部含排序值 + id，对外单 `cursor` 字符串）
- [ ] `FeedTimelineStore.readTimelineByCursor(userId, cursorScore, cursorPostId, limit)`：`ZREVRANGEBYSCORE key (cursorScore -inf LIMIT 0 limit`，同 score 用 postId 字典序兜底去重（跳过已返回的边界成员）
- [ ] `FeedService.findFollowingFeedByCursor(userId, cursor, limit)` 返回 `CursorPageResult`，委托 `CommunityPostService.findPublishedByIds` 批量回填（沿用 #12 过滤已删除逻辑）
- [ ] `FeedController` 新增/扩展 `GET /api/v1/feed/following?cursor={c}&limit={n}`（cursor 空表示第一页），与旧 `page/pageSize` 并存（以是否传 cursor 区分）
- [ ] 测试（TDD 先行）：游标翻页正确、同 score 去重、不丢不重、深翻页与浅翻页结果一致、`hasMore`/`nextCursor` 语义
- [ ] 编译级快验通过；现有 Feed 测试不破坏
