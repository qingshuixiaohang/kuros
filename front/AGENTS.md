<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## 积木清单（新增工具函数或组件前先看这里）

### 数据与请求

- `@/lib/api` — 全部 HTTP 函数与后端 DTO 类型（`fetchPostPage`、`fetchPosts`、`fetchCharacter`、`fetchComments`、`createPost`、`uploadImage`、`ApiError` …）
- `@/lib/community-queries` — react-query hooks（`useCommunityPostQuery`、`useCommentsQuery`、`useLikeMutation`、`useFollowUserMutation`、`usePublishPostMutation` …）
- `@/lib/mock` — 演示数据（`guides`、`toolItems`、`characters`）

### 展示与转换

- `@/lib/post-view` — 视图映射与格式化：`toGuide`、`searchItemToGuide`、`formatPublishedAt`、`formatPostCount`、`extractMarkdownImages`，以及 `DEMO_*` 映射表
- `@/components/community/markdown` — Markdown 渲染
- `@/components/community/search-highlight` — 关键词高亮
- `@/components/community/community-pages` — 页面骨架 `CommunityPageFrame`、`PageHeader`
- `@/components/community/api-fallback-note` — 后端不可用时的降级提示
- `@/hooks/use-overlay` — 浮层开关状态

### 约定

- 路由页面只做组装：`app/<route>/page.tsx` 负责取数与布局，展示逻辑放在 `components/community/*`
- 组件与工具函数用命名导出，文件名 kebab-case
- 测试：`__tests__/` 与被测文件同级，vitest

**复用判据**：新增工具函数或组件前，先在 `lib/` 与 `components/community/` 搜同类实现。确需新建时，在 PR 描述里写一句"为什么不用 `<候选>`"。
