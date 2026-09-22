# rp-06: 前端 Feed 关注 tab 无限滚动

**What to build:** 首页关注 tab 从页码分页改为"加载更多"无限滚动——滚动浏览关注动态时点击加载更多，透传后端返回的 nextCursor 追加下一页，体验连贯且深翻不变慢。

**Blocked by:** rp-04（需 Feed cursor 端点 + CursorPageResult 契约）

**Status:** ready-for-agent

- [ ] 关注 tab API 调用从 `page/pageSize` 改为 `cursor/limit`，解析 `CursorPageResult`（items + nextCursor + hasMore）
- [ ] "加载更多"按钮/滚动触底：透传上一页 `nextCursor` 请求下一页，结果追加到现有列表（不替换）
- [ ] `hasMore=false` 时隐藏"加载更多"、展示到底提示；空状态/未登录引导沿用 #12 行为
- [ ] 列表页（攻略/帖子）保留页码 UI，最小改动（后端 cursor 就绪即可，本片不强制迁无限滚动）
- [ ] 前端类型定义同步（CursorPageResult TS 类型）；`npm run lint` + `npm run build` 通过
- [ ] 遵循项目纯 CSS + 自定义类约定（不引入 Tailwind utility）
