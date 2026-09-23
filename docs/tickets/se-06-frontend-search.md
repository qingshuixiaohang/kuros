# se-06: 前端搜索接入（最小载体）

**What to build:** 社区搜索框输入关键词后，结果来自 ES 全文检索——命中词高亮显示（`<mark>`）、下拉加载更多走 search_after 无限滚动；keyword 为空时行为完全不变。不重做社区 UI、不做独立搜索页。

**Blocked by:** se-04（需 `/api/v1/search` 契约就位）

**Status:** ready-for-agent

- [ ] 新增 `searchPosts` API 层，对接 `GET /api/v1/search`，复用 #13 `CursorPageResult` 类型
- [ ] 社区搜索框 keyword 非空时切到 `searchPosts`（走 ES）；keyword 为空时保持原 `/api/v1/posts` 行为不变
- [ ] 结果渲染 `<mark>` 高亮片段（title/content highlight map）
- [ ] 复用 #13「加载更多」无限滚动（透传 nextCursor / search_after）
- [ ] ES 降级时前端展示"搜索暂不可用"友好提示（不崩、不空白）
- [ ] 前端快验通过（lint + build / `node --check`）；不破坏现有 e2e
