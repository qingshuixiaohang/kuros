# rp-06: 前端 Feed 关注 tab 无限滚动

**What to build:** 首页关注 tab 从页码分页改为"加载更多"无限滚动——滚动浏览关注动态时点击加载更多，透传后端返回的 nextCursor 追加下一页，体验连贯且深翻不变慢。

**Blocked by:** rp-04（需 Feed cursor 端点 + CursorPageResult 契约）

**Status:** done

- [x] 关注 tab API 调用从 `page/pageSize` 改为 `cursor/limit`，解析 `CursorPageResult`（items + nextCursor + hasMore）
- [x] "加载更多"按钮/滚动触底：透传上一页 `nextCursor` 请求下一页，结果追加到现有列表（不替换）
- [x] `hasMore=false` 时隐藏"加载更多"、展示到底提示；空状态/未登录引导沿用 #12 行为
- [x] 列表页（攻略/帖子）保留页码 UI，最小改动（后端 cursor 就绪即可，本片不强制迁无限滚动）
- [x] 前端类型定义同步（CursorPageResult TS 类型）；`npm run lint` + `npm run build` 通过
- [x] 遵循项目纯 CSS + 自定义类约定（不引入 Tailwind utility）

## 实现记录（done）

- `front/src/lib/api.ts`：新增 `CursorPageResult<T>` TS 类型 + `fetchFollowingFeedByCursor({ cursor, limit })`（走 cursor/limit，返回 `{ items, nextCursor, hasMore }`）；保留旧 `fetchFollowingFeed`（offset）不动以最小侵入。
- `front/src/components/community/community-home.tsx`：Feed 组件关注 tab 首页走 `fetchFollowingFeedByCursor({ limit: 20 })`（cursor=null），新增 `nextCursor/hasMore/loadingMore` 状态与 `loadMore()`（透传 nextCursor 追加、按 id 去重、失败即停）；`hasMore` 时显示"加载更多"按钮，`hasMore=false` 显示到底提示；recommend/latest tab 与列表页保持原页码/一次性加载行为不变。移除了 Feed 内未使用的 `followed` 解构。
- `front/src/app/globals.css`：新增 `.feed-load-more` / `.feed-end-note`（复用 `--cyan-soft`/`--muted` 令牌，纯 CSS 自定义类，无 Tailwind）。
- 验证：`npm run lint` 0 error（仅既有无关 warning）；`npm run build` Compiled successfully + TypeScript 通过。
- 偏差：列表页（攻略/资讯）本片未迁无限滚动，符合工单"不强制迁"约定，后端 cursor 端点已就绪待后续消费。
