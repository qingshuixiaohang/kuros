# se-06: 前端搜索接入（最小载体）

**What to build:** 社区搜索框输入关键词后，结果来自 ES 全文检索——命中词高亮显示（`<mark>`）、下拉加载更多走 search_after 无限滚动；keyword 为空时行为完全不变。不重做社区 UI、不做独立搜索页。

**Blocked by:** se-04（需 `/api/v1/search` 契约就位）

**Status:** done

- [x] 新增 `searchPosts` API 层，对接 `GET /api/v1/search`，复用 #13 `CursorPageResult` 类型
- [x] 社区搜索框 keyword 非空时切到 `searchPosts`（走 ES）；keyword 为空时保持原 `/api/v1/posts` 行为不变
- [x] 结果渲染 `<mark>` 高亮片段（title/content highlight map）
- [x] 复用 #13「加载更多」无限滚动（透传 nextCursor / search_after）
- [x] ES 降级时前端展示"搜索暂不可用"友好提示（不崩、不空白）
- [x] 前端快验通过（lint + build / `node --check`）；不破坏现有 e2e

---

## 实现证据（se-06）

**载体选择：复用已有 `/search` 页，不新建。** 社区搜索框（`TopNavigation.submitSearch`）本就 `router.push("/search?q=")` 跳到 `SearchResultsPage`，故改造该页即满足「搜索框输入→ES 结果」，无需重做社区 UI。

**改动清单（6 文件）：**
- `types/community.ts`：`Guide` 加可选 `highlight?: Record<string, string[]>`（仅搜索结果填充，普通浏览 undefined，向后兼容）。
- `lib/api.ts`：新增 `PostSearchItem` 类型（对齐后端 record：无 author 对象/media，带 authorId+authorName+highlight）+ `searchPosts()`（`GET /api/v1/search`，复用 `requestEnvelope` + `CursorPageResult`，默认 limit=10、sort=relevance，透传 cursor）。
- `lib/post-view.ts`：新增 `searchItemToGuide()`（PostSearchItem→Guide，authorName 作作者、透传 highlight、mediaUrls 留空避免为图回 DB）。
- `lib/community-queries.ts`：新增 `useSearchPostsQuery()`（`useInfiniteQuery`，queryKey=[search-posts,keyword]，`getNextPageParam` 透传 nextCursor→search_after，`retry:false` 让 503/错误立即冒泡）。
- `components/community/search-highlight.tsx`（新建）：`HighlightText` 组件。
- `components/community/search-page.tsx`：拆 `EsSearchResults`（keyword 非空）/`BrowseResults`（keyword 空，保留原 offset 分页 /api/v1/posts 行为）。
- `components/community/community-pages.tsx`：`GuideListItem` 的 title/excerpt 改用 `HighlightText` 渲染。

**关键决策：**
1. **高亮安全渲染（不用 dangerouslySetInnerHTML）**：ES highlight 只在命中词两侧插 `<mark>/</mark>`，其余是**未转义原文**——直接 innerHTML 会 XSS。`HighlightText` 按分隔符 `<mark>/</mark>` 切片：命中片段包进真 `<mark>` 元素，其余当纯文本交 React 自动转义。无命中字段（不在 highlight map）回退原文，保证不空白。
2. **useInfiniteQuery 替代手写 useEffect+setState**：初版手写 effect 在体内同步 setState 被 React 19 lint 规则 `react-hooks/set-state-in-effect` 拦（级联渲染）。改用 react-query `useInfiniteQuery` 后翻页/竞态/缓存全托管，组件只做纯派生渲染，无 effect。
3. **三态降级区分**：`ApiError.status===503`（ES 软依赖降级）→「搜索暂不可用，请稍后重试」（**不**回退 Demo，避免把「搜索坏了」误导成「没结果」）；其他错误（后端整体不可用）→ 本地 mock Demo 回退 + 「后端暂不可用」提示（对齐原 UX）；正常无命中 → 空态引导。
4. **keyword 空行为零改动**：空关键词仍走 `useCommunityPostQuery`→`/api/v1/posts` offset 分页，与 se-06 前完全一致。

**前端快验（实测）：**
- `npx tsc --noEmit` → EXIT 0（无类型错误）
- `npm run lint`（eslint）→ EXIT 0（仅 1 个既有 warning：publish-page.tsx 未用 Link，非本次改动）
- `npm run build`（next build / Turbopack）→ EXIT 0，Compiled successfully，`/search` 为 ƒ (Dynamic)
- e2e：现有 5 个 spec 均不覆盖 `/search`（grep 无 search 命中）；非搜索页 `GuideListItem` 无 highlight 时 `HighlightText` 直接返回原文字符串，DOM 与改动前一致 → 不破坏现有 e2e。

**未纳入本工单（属 se-07）：** Canal→RocketMQ→backend→ES→前端 的端到端 compose 冒烟（backend 容器当前未起，仅数据基建 canal/mysql/es/rocketmq 在跑）。
