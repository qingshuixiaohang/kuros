# rp-05: 帖子列表 keyset 游标分页

**What to build:** 帖子列表（最新/热门）的深翻页改走 keyset 游标——翻到第 1000 页和第 1 页一样快，不再扫描丢弃前 offset 行、不再执行 `COUNT(*)`；旧 offset 端点保持兼容。

**Blocked by:** rp-04（需 CursorPageResult 契约 + base64 cursor 编解码）

**Status:** ready-for-agent

- [ ] `CommunityPostRepository` 新增 keyset 查询：
  - latest：排序键 (published_at, id)，谓词 `WHERE (published_at < :ts) OR (published_at = :ts AND id < :id)`，`ORDER BY published_at DESC, id DESC LIMIT n`
  - hot：排序键 (like_count, comment_count, published_at, id)，元组比较（row-value 或展开 OR），`ORDER BY` 同序
  - 保留 category/tag/keyword 过滤条件
- [ ] cursor = (排序键值..., id) 编码为不透明 base64（复用 rp-04 工具）
- [ ] `CommunityPostService.findPublishedByCursor(sort, category, tag, keyword, cursor, limit)` 返回 `CursorPageResult`，作者批量回填沿用 `UserDirectoryFacade.findAuthors`
- [ ] `GET /api/v1/posts?sort=hot|latest&cursor={c}&limit={n}` 与现有 offset 端点并存（以是否传 cursor 区分模式，返回不同契约）
- [ ] 测试（TDD 先行）：latest/hot 两种排序键 keyset 翻页正确性、边界（同 published_at / 同热度）去重不丢不重、`hasMore`/`nextCursor` 语义、offset 端点仍兼容
- [ ] 编译级快验通过；现有帖子列表测试不破坏
