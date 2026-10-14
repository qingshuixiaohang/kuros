# 前端只读审计报告 — `front/`（Next.js 16.3.5 + React 19.2.8 + react-query 5.102.8）

- 审计日期：2026-10-10
- 审计人：omp（coding assistant）
- 范围：`front/src`（58 文件 / 4819 行）、`front/e2e`（6 spec / 999 行）、`front/*.config.*`、`src/app/globals.css`
- 方法：**只读**。全量通读源码 + `grep`/`glob` 取证；另运行了三个非只读命令并记录结果（它们不修改仓库文件）：
  - `npx tsc --noEmit` → 0 error
  - `npx eslint` → 0 error，6 warning（`community-follow-button.tsx:16` 缺依赖 `notify`；`community-home.tsx:5` `Check`/`Telescope` 未使用、`:30` `activeNav` 未使用；`community-post-detail.tsx:6` `ReactNode` 未使用；`publish-page.tsx:3` `Link` 未使用）
  - 上次 Playwright 运行结果 `front/output/playwright/results/.last-run.json` = `{"status":"passed","failedTests":[]}`（未重跑）
- 未运行 `next build`、未重跑 Playwright、未做浏览器视觉验证。所有结论均为读码与 grep 观察到的**已存在事实**，非推测。
- 未修改 `front/` 下任何源码/配置/测试。

### 严重度定义
- **高**：会导致用户可见的错误数据、功能不可用、明显性能问题或违反既定模式（回归型缺陷）。
- **中**：可维护性/可访问性/一致性缺陷，短期不致命，长期放大。
- **低**：风格、冗余、清理项。

---

## ① react-query 使用（key 设计 / invalidation / 三态）

### 高

**[H-1] 全仓 0 处 `useMutation` / `useQueryClient` / `invalidateQueries` / `setQueryData`**
- 位置：`front/src/**`（grep 全仓验证，唯一匹配项只有 `community-queries.ts:1` 的 `useQuery`/`useInfiniteQuery` import 与 `community-home.tsx:6,45,212`）
- 问题：所有写操作（点赞/收藏/评论/删除评论/关注/发布/举报处理）都是裸 `async` 函数 + 本地 `setState`，没有走 `useMutation`。没有 mutation 层意味着：**没有请求去重、没有并发锁、没有失败重试策略、没有 loading/error 统一语义、没有 invalidate 能力**。
- 为什么危险：`community-home.tsx:100-107` 的点赞成功后只改本地 state；`community-post-detail.tsx:202` 发评论成功后手工 `setComments(...)`；`publish-page.tsx` 发布成功后直接 `router.push`。任何一处写操作后，其他查询缓存里的同一资源都是旧值——例如从详情页点赞后回到首页，首页的 `interaction` state 仍是旧值（因为它是 `useEffect + fetch` 拉的，不是同一份 cache）。用户会看到"点赞数对不上"。
- 建议：引入 `useMutation` + `queryClient.invalidateQueries`。点赞类可乐观更新 `setQueryData(["post-interactions", postId])`，失败回滚；发布成功后 `invalidateQueries({ queryKey: ["community-posts"] })` 与 `["hot-topics"]`。

**[H-2] 首页 Feed 每张卡片 1 次 `/posts/{id}/interactions` 请求，N+1**
- 位置：`front/src/components/community/community-home.tsx:80-89`（`PostCard` 内 `useEffect` → `fetchPostInteractions(postId)`）
- 问题：20 条帖子 = 1 次列表请求 + **20 次**互动状态请求。全部串行/并行由浏览器并发上限（HTTP/1.1 6 条）约束，实际是分 4 批完成。
- 为什么：互动状态（liked/favorited/likeCount）是列表渲染必需的，属于典型的 N+1。后端应支持列表接口批量返回，或前端用 `useQueries` 合并 + `staleTime` 复用。
- 建议：把 `fetchPostInteractions` 的结果并入 `useCommunityPostQuery` 的返回（后端 `/posts` 已返回 `viewCount`，加 `likeCount/liked/favorited` 三个字段即可）；过渡方案用 `useQueries({ queries: ids.map(...) })` 至少共享缓存与并发控制。

**[H-3] 同一资源两套独立状态源，首页与详情页不共享缓存**
- 位置：`front/src/components/community/community-home.tsx:82-88` 与 `front/src/components/community/community-post-detail.tsx:83-89`
- 问题：两边各自 `useState<PostInteraction>` + 各自 `fetchPostInteractions(postId)`，key 都不进 react-query。在首页点赞（只改本地 state）→ 新标签打开详情（重新 fetch，拿到的是后端旧值，因为首页那次点赞**有**调后端 API，所以这个例子恰好一致）；但若首页点赞请求失败被 catch 吞掉（`community-home.tsx:83` 的 `.catch(() => {})`），本地仍显示已点赞，详情页显示未点赞。
- 建议：抽 `usePostInteractions(postId)` 放 `community-queries.ts`，key `["post-interactions", postId]`，两处共用。

### 中

**[M-1] 列表 key 与搜索 key 分裂，重叠数据不共享缓存**
- 位置：`front/src/lib/community-queries.ts:7`（`["community-posts", ...]`）与 `front/src/lib/community-queries.ts:25`（`["search-posts", keyword]`）
- 问题：同一批帖子的数据在首页是 `community-posts` 缓存、在搜索页是 `search-posts` 缓存、在攻略页又各拉一次。三次进同一页面三次请求。
- 建议：统一为 `["posts", filters, cursor]`，搜索只是 `keyword` 非空的特例；或至少在文档中明确"这两类 key 故意分离"的理由（目前无注释）。

**[M-2] `useQuery` 未抽 hook，业务聚合逻辑写在组件体内**
- 位置：`front/src/components/community/community-home.tsx:212`（`["hot-topics"]`，queryFn 内 `fetchPosts({ sort: "hot", pageSize: 40 })` + 循环聚合 tag + `slice(0, 5)`）
- 问题：57 行组件内的 667 字符单行。查询语义（拉 40 篇、按 tag 聚合、取 top 5）埋在 JSX 文件里，无法单测、无法复用。
- 建议：移到 `community-queries.ts` 的 `useHotTopicsQuery()`，聚合逻辑放 `src/lib/`。

**[M-3] react-query 客户端配置过薄**
- 位置：`front/src/components/community/community-provider.tsx:10`（仅 `staleTime: 30_000, refetchOnWindowFocus: false`）
- 问题：无 `gcTime`（默认 5min，本地 mock 场景内存压力可忽略但生产需评估）、无 `retry` 全局策略（列表查询默认重试 3 次会拖慢失败反馈，与 `useSearchPostsQuery` 的 `retry: false` 矛盾）、无全局 `onError`（无统一 401 处理）。
- 建议：显式设置 `retry: (count, error) => !(error instanceof ApiError) && count < 1`，`gcTime: 10 * 60_000`。

**[M-4] 9 处组件内手搓取数未迁 react-query**
- 位置：`community-home.tsx:152-158`（Feed 主取数）、`community-home.tsx:188`（loadMore）、`community-post-detail.tsx:87`、`community-post-detail.tsx:186`（评论）、`community-post-detail.tsx:273`（帖子详情）、`community-profile.tsx:105`、`community-profile.tsx:129,141`（公开主页）、`community-follow-button.tsx:14`、`community-admin-reports.tsx:28`、`publish-page.tsx:86`
- 问题：`useState × 3-5`（loading/error/data）+ `useEffect` + `let active = true` 竞态守卫的样板重复 9 遍。`community-profile.tsx:102-112` 与 `:127-145` 甚至有两段近乎相同的 effect（一个有 `void` 一个无）。
- 建议：按资源抽 `useMyProfileQuery` / `usePublicProfileQuery` / `useCommentsQuery` / `usePostQuery` / `useAdminReportsQuery` / `useFollowStateQuery`，全部进 `community-queries.ts`。`community-queries.ts:16-22` 的注释已经把这个论证写清楚了，只是没推广到其余资源。

---

## ② 组件质量（巨型组件 / 重复逻辑 / props drilling）

### 高

**[H-4] `community-home.tsx` 单文件 264 行承载 9 个关注点**
- 位置：`front/src/components/community/community-home.tsx`（`Sidebar`:29、`TopNavigation`:30、`GameLogo`:29、`PostMedia`:65、`PostCard`:74、`guideFromListItem`:109、`Feed`:140、`RightRail`:208、`useCommunityDrawer`:228、`HomeContent`:260）
- 问题：顶栏（含 hover 账户面板、消息面板、搜索表单）、侧栏、Feed 取数+三态+tab、右栏（工具+资讯+热门话题）、卡片（媒体宫格+互动）全在一个文件。24 处 hook。改顶栏要重读整个文件确认不碰 Feed。
- 建议：按目录拆 `src/components/community/home/{top-navigation,sidebar,post-card,feed,right-rail}.tsx`。`useCommunityDrawer` 与 `Sidebar`/`TopNavigation` 已被 `community-pages.tsx` 跨文件复用，拆出去是纯收益。

**[H-5] `GuideListItem` 两份实现，其中一份死代码**
- 位置：`front/src/components/community/community-pages.tsx:16`（带 `HighlightText`，被 `search-page.tsx:70,104` 使用）与 `front/src/components/community/community-guides-api.tsx:20-22`（无高亮）
- 问题：`community-guides-api.tsx:20` 的 `GuideListItem` **全仓无任何调用方**（grep 验证：`community-guides-api.tsx:41` 的 JSX 里没有引用它）。两份渲染结构几乎一致，差异只有高亮。
- 建议：删除 `community-guides-api.tsx:20-22`；若攻略页要复用，从 `community-pages` 导入并按需传 `highlight`。

**[H-6] `postIdBySlug` 映射逻辑两份**
- 位置：`front/src/components/community/community-home.tsx:60` 与 `front/src/lib/post-view.ts:73-78`（`DEMO_POST_ID_BY_SLUG`）
- 问题：`post-view.ts` 的注释自称已收敛，但 `community-home.tsx:60` 仍保留一份 `DEMO_POST_ID_BY_SLUG` 的读取副本（用于 demo 帖子的详情链接）。两处都要跟着 `mock.ts` 的 id 走，漏一处就是 404。
- 建议：`community-home.tsx:60` 改为从 `post-view.ts` 导入。

### 中

**[M-5] 频道抽屉 JSX 在 `community-home.tsx:260` 与 `community-pages.tsx:14` 各写一遍**
- 位置：`community-home.tsx:260`（`HomeContent`）、`community-pages.tsx:14`（`FrameContent`）
- 问题：`drawer-backdrop` + `mobile-drawer` + `drawer-header` + `Sidebar drawer` 四段结构逐字重复。`ui-regression.spec.ts:30`（键盘开关抽屉）只覆盖了首页路径。
- 建议：抽 `<ChannelDrawer>` 组件。

**[M-6] props drilling：`CommunityPageFrame` 的 query 双模式**
- 位置：`front/src/components/community/community-pages.tsx:9-14`
- 问题：`query`/`onQueryChange` 可选，缺省时组件内部自持 `localQuery`。`FrameContent` 同时处理"受控/非受控"两种搜索框语义，调用方（`community-guides-api.tsx:41` 传 query、`characters-page.tsx` 不传）行为不一致。
- 建议：拆成 `CommunityPageFrame`（无搜索）与 `SearchablePageFrame`（受控 query），或让 frame 只负责布局、搜索框由页面自选。

**[M-7] `CommunityFollowButton` 双模式 props**
- 位置：`front/src/components/community/community-follow-button.tsx:7`（`targetUserId` + `fallbackKey` 同时存在）
- 问题：`targetUserId` 为真走 API，为假走 localStorage demo 状态。同一"关注"按钮在 PostCard / PostAuthorCard / 两个 ProfileHero 四个场景下语义不同，穿透两层组件才到按钮。
- 建议：让关注状态统一走一个 `useFollowState(userId, fallbackKey)` hook，按钮只消费布尔值；或明确 `fallbackKey` 只用于 demo 模式并在 API 接入后删除。

**[M-8] `community-interactions.tsx` 单文件 60 行混合 6 类状态**
- 位置：`front/src/components/community/community-interactions.tsx:18-19`
- 问题：auth（loggedIn/user/sessionRestoring）+ demo 三件套（followed/liked/bookmarked，localStorage `wuthering-community-v1`）+ toast + 登录弹窗 + pendingAction 全在一个 Provider。行 18 单行 12 个 `useState`。
- 建议：至少拆 `AuthProvider`（会话恢复+登录弹窗）与 `DemoInteractionProvider`；pendingAction 逻辑跟随 auth。

**[M-9] 代码格式：全文件单行压缩，无 prettier**
- 位置：`front/src/components/community/*.tsx`、`src/lib/*.ts` 共 **47 行超过 400 字符**（`community-home.tsx:54` 2027 字符、`community-admin-reports.tsx:62` 2032 字符、`community-pages.tsx:14` 1178 字符）
- 问题：`package.json` 无 prettier 依赖、无 `.prettierrc`、`eslint.config.mjs` 无格式规则。git log 显示最近提交已引入 `husky/lint-staged`（`6cfa235` 提到 pre-commit），但格式未纳入。行号引用、diff 审阅、 blame 全部受影响。
- 建议：加 prettier + `printWidth 160`（适配现有风格，diff 最小），纳入 lint-staged。

---

## ③ 类型安全（any / 断言滥用）

### 中

**[M-10] `api.ts:127` 用 `undefined as unknown as ApiEnvelope<T>` 伪造返回值**
- 位置：`front/src/lib/api.ts:127`（`if (response.status === 204) return undefined as unknown as ApiEnvelope<T>;`）
- 问题：`requestEnvelope` 返回类型是 `ApiEnvelope<T> | undefined`，204 分支其实不需要断言——直接 `return undefined;` 就满足类型。这个 `as unknown as` 是冗余且危险的样板（暗示此处有类型漏洞）。
- 建议：改为 `return undefined;`。全仓仅此 1 处 `as unknown`（grep 验证）。

**[M-11] `api.ts:134` `envelope?.data as T` 空 envelope 静默失真**
- 位置：`front/src/lib/api.ts:132-135`
```ts
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const envelope = await requestEnvelope<T>(path, init);
  return envelope?.data as T;   // envelope 为 undefined 时返回 undefined，但类型是 T
}
```
- 问题：`requestEnvelope` 只在 204 返回 `undefined`，所以 204 的 DELETE 会得到 `undefined as T`。对 `void` 返回无害，但若将来有 `request<T>(...)` 用于非 204 路径且 envelope 缺失，调用方拿到 `undefined` 而 TS 认为安全。
- 建议：拆 `requestVoid`（204）与 `request<T>`（必须 envelope，缺失时 `throw new ApiError(0, "EMPTY_ENVELOPE", ...)`）。

**[M-12] 错误响应体未用 zod 校验**
- 位置：`front/src/lib/api.ts:113-117`（`error = await response.json() as { code?: string; message?: string }`）
- 问题：后端返回 500 且 body 是 HTML 时 `json()` 抛错被 catch 吞掉（可接受），但 body 是 `{"error":"..."}` 或 `{"detail":[...}` 时会被静默转成 `{code: undefined, message: undefined}`，前端只显示 "API request failed"。`zod@4.6.4` 已在依赖但只用于 `publish-page.tsx` 表单。
- 建议：`const ErrorBody = z.object({ code: z.string().optional(), message: z.string().optional() }).catchall(z.unknown())`，解析失败保留 HTTP status。

**[M-13] `publish-page.tsx:88` 把后端任意 category 断言成表单枚举**
- 位置：`front/src/components/community/publish-page.tsx:88`（`post.category as PublishFormValues["type"]`）
- 问题：后端 category 是自由字符串（`ApiPost.category: string`），表单只接受固定枚举。后端返回"同人创作"时，断言让它"通过"，然后 `defaultValues` 里是非法值，react-hook-form 的 zod resolver 会把它报成 validation error，用户看到莫名的表单错误而不是"该帖子类型不受支持"。
- 建议：`const type = publishTypes.includes(post.category) ? post.category : "GENERAL"`，或先用 zod `safeParse` 分流。

### 低

**[L-1] 生产类型依赖 mock 数据推导**
- 位置：`front/src/components/community/community-pages.tsx:7,16`（`(typeof guides)[number]`）
- 问题：`GuideListItem` 的 props 类型是 `guides[number]`，而 `guides` 是 `src/lib/mock.ts` 的 demo 数据。`search-page.tsx` 传给它的 `Guide` 来自 `src/lib/post-view.ts` 的 `toGuide`——两者靠结构兼容碰巧对上。一旦 `mock.ts` 的字段与 `types/community.ts` 的 `Guide` 漂移，编译期才发现。
- 建议：改为 `import type { Guide } from "@/types/community"`。

**[L-2] `community-pages.tsx:9` `activeNav?: string` 无约束**
- 问题：任意字符串都能传，拼错无编译错误。`community-home.tsx:30` 的 `activeNav` 甚至**完全未使用**（eslint warning 已报）。
- 建议：`activeNav?: NavKey`（联合类型），或删掉未使用的 prop。

---

## ④ 状态管理边界

### 中

**[M-14] `CommunityDemoProvider` 一肩挑 6 类状态**
- 位置：`front/src/components/community/community-interactions.tsx:18-19`
- 问题：auth + demo 三件套（localStorage key `wuthering-community-v1`）+ toast + 登录弹窗 + pendingAction。Provider 的 context value 每次任意 state 变化都重新生成，所有消费组件（`useCommunityDemo()` 在 8 个组件中被调用）全部重渲染。
- 建议：拆 Provider + `useMemo` context value；或拆成 auth / demo / toast 三个 context。

**[M-15] "已点赞/已关注"双源真相，localStorage 与 API 可能不一致**
- 位置：`front/src/components/community/community-home.tsx:96-107`、`front/src/components/community/community-follow-button.tsx:20-27`
- 问题：有 `postId` 走真 API，无则走 localStorage。用户未登录时点赞写入 localStorage；登录后同一帖子读到 API 的 `liked: false`——UI 显示未点赞但 localStorage 有记录，再次点击走 API 分支，行为分裂。
- 建议：明确"登录前点赞仅本地预览，登录后需重新点赞"的产品规则并注释；或在 `sessionRestoring` 结束后清理 localStorage 中的已登录用户数据。

**[M-16] 草稿用另一套 localStorage**
- 位置：`front/src/components/community/publish-page.tsx:27`（`wuthering-community-publish-draft-v2`）
- 问题：demo 三件套 key `wuthering-community-v1`、草稿 key `wuthering-community-publish-draft-v2`、版本号策略各写各的。草稿 zod 校验失败时无迁移路径（v2 是手写版本号，读不到就丢弃）。
- 建议：统一 `src/lib/storage.ts`，`readVersioned(key, schema)`。

**[M-17] 会话恢复 3 次重试 × 1500ms 硬编码**
- 位置：`front/src/components/community/community-interactions.tsx:23-44`
- 问题：`sessionRestoring` 期间整个应用处于"未登录"态，用户点任何需登录操作都会弹登录框。后端慢时最坏 4.5s 白屏态。
- 建议：把会话恢复纳入 react-query（`["session"]` query），`staleTime: Infinity`，一次成功即不再重试；失败态与 loading 态分开渲染。

---

## ⑤ 性能（re-render / 图片 / bundle）

### 高

**[H-7] 首页 Feed 每卡 1 次请求（N+1）——同 H-2**
- 位置：`front/src/components/community/community-home.tsx:80-89`
- 额外证据：`publish.spec.ts` 与 `community-shell.spec.ts` 的 e2e 用例只断言了 UI 呈现，没有断言请求数量，所以这个退化不会被测试发现。

### 中

**[M-18] HeroCarousel 同时渲染全部 slide 的 `Image fill`**
- 位置：`front/src/components/community/community-carousel.tsx:185-191`
- 问题：3 张 5K webp（`bannerSlides` 在 `community-home.tsx:52-56` 指向 `/art/修-2.6版本PV预告图 5K.webp` 等）全部挂载，非当前 slide 只是 `opacity` 隐藏 + `aria-hidden`。`fill` + 未设尺寸 → 每张都触发一次布局测量。移动端首屏即下载 3 张原图。
- 建议：只渲染 `currentIndex` 与 `currentIndex ± 1`（预加载相邻），或至少给非当前 slide 的 `Image` 加 `loading="lazy"` + `priority={false}`。

**[M-19] 两处原生 `<img>` 绕过 `next/image` 优化**
- 位置：`front/src/components/community/community-carousel.tsx:16`、`front/src/components/community/markdown.tsx:16`
- 问题：`eslint-disable` 注释说明原因是"用户媒体域名不可预配置"。`community-carousel.tsx:16` 渲染的是 `bannerSlides`（固定本地路径 `/art/*.webp`）——**这批图完全可以用 `next/image`**，只有 `markdown.tsx` 渲染用户正文图片才真正需要 `<img>`。carousel 这条是误伤。
- 建议：`community-carousel.tsx` 改回 `next/image`（`/art/` 是本地静态资源，`next.config.ts` 已默认放行）；`markdown.tsx` 保留 `<img>` 但补 `decoding="async"` 与显式宽高占位防 CLS。

**[M-20] `publish-page.tsx:171` 上传媒体 `unoptimized`**
- 位置：`front/src/components/community/publish-page.tsx:171`
- 问题：`<Image ... unoptimized />` 让上传的原图直接下发。上传的封面可能是几 MB 的手机照片。
- 建议：确认 `next.config.ts` 的 `remotePatterns` 是否覆盖媒体域名；若已覆盖则去掉 `unoptimized`（git log `ea16c95` 显示曾为修 500 加过白名单，说明域名已通）。

**[M-21] 无 `React.memo`、无列表项 memoization**
- 位置：全仓 grep `React.memo` = 0 匹配
- 问题：`TopNavigation` 内有 `useQuery`（`community-home.tsx:45`），其父组件任何 state 变化（如 `HomeContent` 的 `drawerOpen`）会重渲染整棵树。`community-interactions.tsx` 的 context value 未 memo，进一步放大。
- 建议：`PostCard`、`CommentComposer`、`TopNavigation` 加 `memo`；`community-interactions.tsx` 的 context value 加 `useMemo`。

**[M-22] 热门话题客户端拉 40 篇帖子做聚合**
- 位置：`front/src/components/community/community-home.tsx:212`
- 问题：为渲染 5 个 tag 拉 40 篇完整帖子（含 excerpt/content？——`fetchPosts` 走 `fetchPostPage`，列表接口不含 content，影响可控，但仍是 40 条 JSON）。
- 建议：后端加 `/api/v1/posts/hot-topics` 聚合端点；或降低 `pageSize` 到 20 并接受精度损失。

**[M-23] 无 skeleton loader，loading 全是文字**
- 位置：`community-home.tsx:206`（"正在整理漂泊者的最新内容…"）、`community-profile.tsx`、`community-post-detail.tsx` 等
- 问题：CLS 风险 + 感知性能差。grep `skeleton|shimmer` = 0。
- 建议：为 Feed / 评论列表 / profile 加骨架屏（已有 `feed-status` class 可复用样式）。

### 低

**[L-3] 字体三套 + `preload: false`**
- 位置：`front/src/app/layout.tsx`
- 问题：Geist + Geist Mono + Noto Serif SC（weight 900）。中文 serif 900 是体积大户。`preload: false` 说明已知问题但未解决。
- 建议：确认 Noto Serif SC 的实际使用范围（grep `font-serif`/CSS 引用），若仅用于标题则 `display: "swap"` + 限定 `weight: 900` 已够；否则考虑 `next/font/local` 子集化。

---

## ⑥ a11y 与三态覆盖

### 高

**[H-8] 角色详情页未知 id 回退到第一个角色**
- 位置：`front/src/components/community/characters-page.tsx:83`（`characters.find((item) => item.id === id) ?? characters[0]`）
- 问题：访问 `/characters/不存在的id` 显示"第一个角色"的完整资料，URL 与内容不符。仓库自己已在 4 个页面建立了正确模式：`community-post-detail.tsx:289`（`status === "notFound"` → "帖子不存在或已删除"）、`news-page.tsx:31-38`、`echoes-page.tsx:22-29`（注释自称"架构巡检 #2 同模式"）、`tools-page.tsx:23-26` 都渲染"不存在"态。这是回归型不一致——且 `echoes-page.tsx:23` 的注释表明这个坑已经踩过一次并专门修过。
- 建议：`const character = characters.find(...); if (!character) return <NotFoundState />;`（与 `echoes-page.tsx` 同款）。

### 中

**[M-24] HeroCarousel 自动播放无暂停控件（WCAG 2.2.2 违规）**
- 位置：`front/src/components/community/community-carousel.tsx:144`（`setInterval(5000)`）、`:118`（圆点）
- 问题：5 秒自动切换，仅 hover/focus 暂停，无暂停按钮。有前庭障碍的用户无法停止运动。
- 建议：加一个暂停/播放按钮（`aria-pressed` 或 `aria-label` 切换），并在 `prefers-reduced-motion: reduce` 时默认暂停（当前只处理了 `scroll-behavior`，见 `community-carousel.tsx:19-29`）。

**[M-25] 筛选按钮组无 `aria-pressed` / `role="group"` / fieldset**
- 位置：`front/src/components/community/characters-page.tsx:35,38,44,50`（4 组）、`front/src/components/community/community-guides-api.tsx:41`（filter-tabs）、`front/src/components/community/news-page.tsx:21`、`front/src/components/community/echoes-page.tsx:19`
- 问题：屏幕阅读器只读"按钮 全部"，无法知道这是"分类筛选"且"全部"是当前选中项。选中态只靠 class `is-active`。
- 建议：每组外包 `<div role="group" aria-label="属性筛选">`，按钮加 `aria-pressed={element === item}`；`characters-page.tsx` 的 `<span className="catalog-tabs-label">属性</span>` 改为 `<legend>`/`<span id=...>` + `aria-labelledby`。

**[M-26] 多处 loading/empty 无 `role="status"` / `aria-live`**
- 位置：`front/src/components/community/community-home.tsx:203`（Feed loading）、`front/src/components/community/characters-page.tsx:76`（`empty-state`）、`front/src/components/community/community-guides-api.tsx:41`（loading/empty）、`front/src/components/community/echoes-page.tsx`、`front/src/components/community/tools-page.tsx`
- 问题：对比：`community-post-detail.tsx:226`（`role="status"`）、`community-interactions.tsx:59`（toast `role="status"`）、`publish-page.tsx:190`（`aria-live="polite"`）都做对了。这几处是遗漏，不是没意识。
- 建议：统一加 `role="status"`；Feed 的 tab 切换结果区加 `aria-live="polite"`。

**[M-27] carousel 圆点 `role="tablist"` 缺 `aria-controls` 与 tabpanel**
- 位置：`front/src/components/community/community-carousel.tsx:196`（banner 圆点 `role="tablist"`）、`:188`（slide `aria-hidden`，无 `role="tabpanel"`/`id`）
- 问题：`tablist` 模式要求 tab 与 tabpanel 关联（圆点 `aria-controls` → slide `id`）。当前 slide 容器无 `role="tabpanel"` 无 `id`，圆点也无 `aria-controls`/`aria-selected`（用的是 `aria-current`）。左右键在 tablist 内切换已实现（好在）。
- 建议：slide `<article>` 加 `role="tabpanel"` + `id`，圆点加 `aria-controls` + `aria-selected`。

**[M-28] 无 skip-link**
- 位置：`front/src/app/layout.tsx` / `front/src/components/community/community-pages.tsx:14`
- 问题：键盘用户每次进页面要先 Tab 过整个顶栏（10 个频道链接）才到内容。
- 建议：`<main>` 前加 `<a className="skip-link" href="#main-content">跳到主内容</a>`，`main` 加 `id="main-content"` + `tabIndex={-1}`。

### 低

**[L-4] 装饰性图片 alt 策略不统一**
- 位置：`community-home.tsx:53`（logo 图标 `alt=""` + 相邻链接有 `aria-label`，正确）、`community-home.tsx:220`（工具图标 `alt=""`，相邻 `<strong>{title}</strong>`，正确）、`community-home.tsx:222`（话题封面 `alt=""`，相邻 `#{tag}`，正确）
- 说明：这几处实际都对（alt 为空 + 相邻文本可访问）。但 `community-profile.tsx:62` 的帖子缩略图 `alt=""` 而相邻有完整标题，同样正确。此项仅记录"依赖相邻文本"的隐性契约，建议加注释防止后人补 alt。

---

## ⑦ 样式规范

### 中

**[M-29] 单 `globals.css` 923 行 + 314 个不同 hex 色值（共 480 处出现），无设计令牌**
- 位置：`front/src/app/globals.css`（923 行）
- 问题：480 处 hex 字面量（314 个不同色值；另有 57 处 3 位 hex）散落（`#e8edf1`、`#a8abb1`、`#102b3d`…），只有零星几个 `var(--cyan)`。改主题色要全局替换；对比度无法审计。无 CSS Modules、无 Tailwind、无 stylelint。
- 建议：先抽出 `:root` 令牌层（`--surface-1`、`--text-primary`、`--border-subtle`、`--brand-cyan`…），用 `grep -o '#[0-9a-f]\{6\}' | sort | uniq -c | sort -rn` 找出 top 20 色值优先令牌化。CSS 可按节拆 `globals.css` → `@import` 多个文件，不必一次重构。

**[M-30] 7 处 `!important`**
- 位置：`front/src/app/globals.css:16,84,95,415,420,823,865`
- 问题：`:16` 的 `.top-search { display: none !important }` 在 media query 里压 `.mobile-search-button`。`!important` 会破坏后续所有覆盖，包括用户自定义与第三方。
- 建议：用 `:where()` 降低优先级或重排选择器特异性；`:16` 可用 `.top-search` 在 mobile 下默认 `display: none`，desktop 媒体查询里覆盖为 `flex`（去掉 important）。

**[M-31] 5 处 inline style**
- 位置：`front/src/components/community/characters-page.tsx:94,100,109,121`、`front/src/components/community/community-home.tsx:68`
- 问题：`characters-page.tsx:94,100,109,121` 是 `marginTop`/`display`/`verticalAlign` 这类纯静态值，纯粹是可以进 class 的。`:109,121` 的 `display: "inline", verticalAlign: "text-bottom", marginRight: 6` 在 `<h2>` 里配图标，重复 2 次。
- 建议：加 `.entity-note h2 svg { display: inline; vertical-align: text-bottom; margin-right: 6px; }` 与 `.entity-tags { margin-top: 12px; }` 等 utility class。

---

## ⑧ mock.ts 残留（哪些页面还在用）

### 高

**[H-9] mock 页接真数据——进行中（2026-10-14）**

> 进度：characters（PR #57）、news（`4b0641d` + `260d35f`）、echoes（`6e74123` + `0509f50`）已完成；
> 剩 **tools / creations 两页**。每条都是「后端建域 + 前端三态」两个提交，样板见 `git show 4b0641d`。
> 新增 spec：`e2e/news.spec.ts`（5 例）、`e2e/echoes.spec.ts`（6 例）。
> 基线：e2e 106 passed / 0 failed、vitest 39/39、tsc 0 error。

**[H-9] 6 个页面以 mock 为主数据源，无任何加载/错误态**
- 位置：
  - `front/src/components/community/characters-page.tsx:7`（`characters`）
  - `front/src/components/community/echoes-page.tsx:6`（`echoSets`）
  - `front/src/components/community/tools-page.tsx:7`（`characters`, `toolItems`）
  - `front/src/components/community/news-page.tsx:8`（`newsItems`）
  - `front/src/components/community/creations-page.tsx:6`（`characters`）
  - `front/src/components/community/community-home.tsx:210`（右栏 `newsItems`）
- 问题：这 6 处页面**没有任何异步**，所以没有 loading/error/empty 三态（`characters-page.tsx:76` 只有一个纯前端筛选的空态）。`characters-page.tsx:137` 自己都写着"该页面为 Demo 图鉴内容，后端接入后可展示实时材料与面板数据"。e2e 也全绿，因为 mock 永远返回 200。
- 为什么高：这不是"fallback"而是**主路径**。用户看到的角色/声骸/资讯/同人数据全是写死的 131 行 demo 数据。任何一个后端端点都没接。
- 建议：明确每个页面的后端契约（角色图鉴、声骸图鉴、资讯、同人创作各自一个 `/api/v1/*`），按 `community-queries.ts` 的模式接 `useQuery`，保留 mock 为降级 fallback（对齐 `search-page.tsx:13-17` 与 `community-guides-api.tsx:10` 已有的 fallback 模式）。

### 中

**[M-32] 4 处 mock 作为降级 fallback（可接受，但需注释明确）**
- 位置：`front/src/components/community/community-home.tsx:14`（guides fallback）、`front/src/components/community/community-guides-api.tsx:10`、`front/src/components/community/search-page.tsx:7`、`front/src/components/community/community-post-detail.tsx:13`（demo slug 初始渲染）
- 问题：模式本身正确（`apiUnavailable ? mock : api`），但 `community-home.tsx:132` 的 `guides.find(...)`（demo 媒体匹配）与 `:175` 的本地 keyword 过滤都是隐式的"后端字段不齐时的补丁"，没有说明何时会走到。
- 建议：每处 fallback 加一行注释说明触发条件；`community-home.tsx:132` 的 `demoMedia` 匹配应在后端返回 `mediaUrls` 后删除。

**[M-33] 1 处 mock 仅用于类型推导**
- 位置：`front/src/components/community/community-pages.tsx:7,16`
- 见 [L-1]。应改为 `import type { Guide }`。

---

## ⑨ e2e 覆盖缺口

### 现状
`front/e2e/` 6 个 spec 共 999 行，上次运行 `passed`。已覆盖：
- `admin-entry.spec.ts`（3）：管理入口可见性 + 无权限页
- `community-shell.spec.ts`（9）：横向滚动回归、300px 工具栏、六个工具入口、主题图标、Feed 媒体/宫格、Banner 轮播、分享剪贴板、窄屏抽屉
- `post-detail.spec.ts`（6）：轮播配图、sticky 双栏、移动端、Markdown 混合渲染、无标题正文
- `profile.spec.ts`（9 + viewport 循环）：个人中心五个分区、编辑/删除、公开主页、游客登录引导
- `publish.spec.ts`（8 + viewport 循环）：聚焦布局、草稿恢复、工具栏全套、图片上传发布、多图排序、编辑回填
- `ui-regression.spec.ts`（9 + 循环）：多路由 × 视口横向滚动、键盘抽屉、频道入口、登录弹窗焦点陷阱、reduced-motion、账户面板、评论编辑器

### 高

**[H-10] `/search` 全流程零覆盖**
- 位置：无对应 spec
- 未覆盖：ES keyword 检索、无限滚动 `getNextPageParam` 翻页、无命中空态引导、**503「搜索暂不可用」降级**、后端整体不可用时的本地 demo 回退、`useInfiniteQuery` 的 keyword 切换竞态
- 为什么高：`search-page.tsx:53` 的 503 特判与 `useSearchPostsQuery` 的游标契约是全仓最复杂的客户端逻辑（`community-queries.ts:13-31` 有 10 行注释专门解释），却没有任何测试。游标深翻 / 竞态 / 空态是用户高频路径。
- 建议：`e2e/search.spec.ts`：① keyword 命中 + 无限滚动到底；② 无结果空态；③ route `/api/v1/search` 返回 503 → 断言"搜索暂不可用"文案且不崩；④ mock 后端 500 → 断言 demo 回退列表；⑤ 快速改 keyword → 断言列表与 keyword 一致（竞态）。

**[H-11] `/?tab=following` 关注流零覆盖**
- 位置：无对应 spec
- 未覆盖：游标分页 `fetchFollowingFeedByCursor`（`community-home.tsx:157,188`）、401 未登录引导（`community-home.tsx:202` 的 `feedIssue.kind === "unauthorized"` 分支）、`hasMore` 加载更多、`feed-end-note` 到底态
- 为什么高：这是**唯一**走游标分页的页面路径，且 `community-home.tsx:141-198` 那段手搓取数（`loadedKey`/`requestKey`/`reloadKey`/`feedIssue` 四状态）是全仓最脆弱的手写逻辑——tab 切换、query 变化、重试三条路径的组合没有被任何测试锁定。
- 建议：`e2e/following-feed.spec.ts`：① 未登录访问 → 401 引导；② 已登录 → 首屏 + 加载更多 + 到底 note；③ tab 从 following 切回 recommend 再切回 → 状态不串。

**[H-12] mutation 全链路零覆盖**
- 位置：无对应 spec（`post-detail.spec.ts` 只测渲染，`publish.spec.ts` 只测编辑器交互）
- 未覆盖：点赞/取消点赞（`community-home.tsx:100-107`、`community-post-detail.tsx:105`）、收藏（`:106`）、发评论（`community-post-detail.tsx:199-211`）、删评论（`:213`）、关注/取关（`community-follow-button.tsx`）、发布提交（`publish-page.tsx` publish 函数）、举报提交（`community-report-dialog.tsx`）、审核台处理举报（`community-admin-reports.tsx:28`）
- 为什么高：这些路径**全部没有 `invalidateQueries`**（见 H-1），所以"写后读"一致性完全靠手写 `setState`。没有任何测试能在回归时发现"点赞后首页与详情页不一致"。`admin-entry.spec.ts:30` 测了"无权限页"，但没测"管理员真正处理一条举报"。
- 建议：至少补 3 个核心 mutation spec：点赞（首页 + 详情页双断言）、发评论（成功后列表出现 + 计数 +1）、审核台处理举报（列表刷新）。

### 中

**[M-34] `/guides` 列表筛选与分页零覆盖**
- 位置：无对应 spec
- 未覆盖：`community-guides-api.tsx:41` 的 category / tag / sort 切换、`useCommunityPostQuery` 的 `placeholderData: keepPreviousData` 行为、`apiUnavailable` 时的 `filterFallback`（`community-guides-api.tsx:35`）、分页按钮
- 建议：`e2e/guides.spec.ts`，重点断言筛选切换时旧数据不闪（keepPreviousData）与 API 失败时的降级。

**[M-35] 四个纯 mock 页面零覆盖**
- 位置：无对应 spec
- 未覆盖：`/characters`（筛选 + 详情 + **未知 id 回退缺陷 H-8**）、`/echoes`、`/tools`、`/creations` 及各自详情页
- 为什么：`ui-regression.spec.ts:18` 的横向滚动循环**只跑首页**，这四个页面的响应式完全没有断言。H-8 的 `?? characters[0]` 缺陷如果有测试一查就暴露。
- 建议：把 `ui-regression.spec.ts` 的 route 数组加上这四个页面；补一个"未知 id 显示不存在态"的用例（会立即失败，即 H-8 的回归测试）。

**[M-36] 无 console error / hydration 断言**
- 位置：无对应 spec（`playwright.config.ts` 未配置全局 console 监听）
- 问题：React 19 + `"use client"` + 16 个 `force-dynamic` 页面的 hydration 风险完全无防护。
- 建议：`playwright.config.ts` 加 `page.on("console")` / `page.on("pageerror")` 收集，或在 `ui-regression.spec.ts` 每个用例后断言无 error 级 console。

**[M-37] 无全键盘遍历 / 焦点顺序断言**
- 位置：`ui-regression.spec.ts:55` 只测了登录弹窗的焦点陷阱
- 未覆盖：skip-link（本身不存在，见 M-28）、carousel 圆点 `role="tab"` 的左右键 + `aria-controls`（M-27）、筛选组的键盘操作（M-25）
- 建议：补一个 `a11y.spec.ts`：Tab 顺序、Escape 关闭各浮层、carousel 键盘导航。

### 低

**[L-5] e2e 全是 UI 呈现断言，无网络层断言**
- 位置：`front/e2e/*.spec.ts`
- 问题：没有任何 `page.route()` mock 或请求计数。H-2 的 N+1（20 次 `/interactions`）与 H-7 类性能回归不会被发现。
- 建议：关键用例加 `page.waitForRequest` / `route` 计数。

---

## 总体结论 — Top 5 风险

| # | 风险 | 位置 | 影响 |
|---|---|---|---|
| 1 | **零 mutation 层 + 零 invalidation**：所有写操作靠手写 `setState`，"写后读"一致性无保障 | 全仓（H-1、H-3） | 点赞/评论/关注后数据不一致；无法乐观更新；无并发保护 |
| 2 | **首页 Feed N+1**：20 帖 = 21 次请求 | `community-home.tsx:80-89`（H-2） | 首屏慢、移动端弱网不可用 |
| 3 | **6 个页面仍是纯 mock 主数据源**：角色/声骸/资讯/同人/工具 5 类内容无后端接入，无三态 | `characters/echoes/tools/news/creations` + 首页右栏（H-9） | 用户看到的是一半写死的数据；e2e 全绿掩盖了这点 |
| 4 | **`/search` 与 `/?tab=following` 两条最复杂路径零 e2e 覆盖**：ES 游标 + 503 降级 + 手写四状态取数 | 无 spec（H-10、H-11） | 全仓最脆弱代码无回归防线 |
| 5 | **回归型缺陷：`/characters/{未知id}` 冒充第一个角色** | `characters-page.tsx:83`（H-8） | 与仓库自己 4 个页面的 404 模式相悖，用户看到错误内容 |

### 值得保留的好实践（不要在重构中丢失）
- `community-queries.ts:13-22` 对 `useInfiniteQuery` 与 `retry: false` 的论证注释——应作为全仓取数模式推广。
- `search-page.tsx:47-53` 的三态降级（503 → 友好提示 / 其他错误 → demo 回退）是全仓最完整的降级设计。
- `use-overlay.ts`（焦点陷阱 / Esc / 焦点归还）与登录弹窗的 `useDismiss`/`useRole`/`FloatingFocusManager` 组合是干净的 a11y 实现。
- `community-post-detail.tsx:173-174` 的注释明确拒绝"假评论种子"——数据诚信意识正确。
- `characters/echoes/tools/news` 四页的未知 id 404 模式（除 `characters-page.tsx:83` 外）一致且正确。
