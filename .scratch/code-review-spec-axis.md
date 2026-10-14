# Code Review — Spec 轴审查报告

- 仓库：`G:\kuros-web`，分支 `fix/batch-a-security`，HEAD `afe4e3a`
- 固定点：`5ca42fb884492091872cfad146a4f93566aa5d18`
- Diff：`git diff 5ca42fb...HEAD`，23 个提交，31 文件，+3338 / −849
- 审查轴：**Spec**（是否忠实实现来源规格）。Standards 轴不在本次范围。
- Spec 来源：
  1. `.scratch/audit-frontend.md`（前端审计 52 条，H-1/H-2/H-3/H-12）
  2. `docs/learning/16-frontend-mutation-layer.md`（批次：前端审计 E / H-12）
  3. `docs/learning/17-test-baseline-cleanup.md`（批次：测试基线清洗）
  4. `.scratch/improvement-plan.md` Batch E（E1–E5）作为范围参照
- 范围声明（有意为之，已逐项核对，见 §4）：
  - H-12 mutation 层六类写操作：点赞/收藏/评论/关注/发布/举报处理
  - 测试基线清洗：3 个 e2e 失败修复、vitest 引入、CI 补步骤
  - 明确不做：首页 Feed 迁 react-query；H-9 六页 mock 接真数据

---

## 一、H-12 mutation 层：六类写操作逐项核对

Spec 依据（16 号文档 §二）："把六类写操作（点赞/收藏/评论/关注/发布/举报处理）统一纳入 react-query 的 mutation 层"；
§一 Result："mutation 从 1 处 → 10 处，`await <api 写函数>` 直调归零"。

### 1.1 实现状态矩阵

| 写操作 | 读 hook | 写 hook | onSuccess 失效 key | e2e 覆盖 |
|---|---|---|---|---|
| 点赞 | `usePostInteractionsQuery` | `useLikeMutation`（原已有） | `post-interactions` + `community-posts` + `search-posts` + `feed-interaction-states` | ✅ 3 用例 |
| 收藏 | 同上 | `useFavoriteMutation` | 同上（4 个） | ✅ 2 用例 |
| 发评论 | `useCommentsQuery` | `useCreateCommentMutation` | `post-comments` | ❌ 无 |
| 删评论 | 同上 | `useDeleteCommentMutation` | `post-comments` | ❌ 无 |
| 关注 | `useUserFollowQuery` | `useFollowUserMutation` | `user-follow` + `feed-interaction-states` | ❌ 无 |
| 发布/更新 | — | `usePublishPostMutation` | `community-posts` + `search-posts` + `post-interactions` | ⚠️ 只测提交请求体，未测缓存失效 |
| 举报处理 | `useAdminReportsQuery` | `useHandleReportMutation` | `admin-reports`（带 filter） | ✅ 2 用例 |
| **举报提交** | — | **无**（仍是裸 `await createReport`） | 无 | ❌ 无 |

全部 hook 集中在 `front/src/lib/community-queries.ts`，组件层无一处直接调写 API
（`grep "await \(likePost\|favoritePost\|createComment\|handleReport\|...\)" src/` 仅剩 `createReport` 与上传/删除图片，见 §2.2）。

### 1.2 修复的四个真 bug 与 spec 描述一致

16 号文档 §三列的四个 bug，diff 中都能对上，实现与文档论证一致：

1. **评论本地 unshift 破坏"最热"排序** → 已改为 invalidate 后重取（`community-post-detail.tsx:366-368` 注释 + `useCommentsQuery`）。
2. **举报处理完还留在待处理队列** → 已去掉本地 `map`，改为 invalidate `["admin-reports", status ?? "ALL"]`（`community-admin-reports.tsx:49-60`），queryKey 与失效 key 精确对齐。
3. **关注按钮只更新自己这一个实例** → `useUserFollowQuery`/`useFollowUserMutation` 共享 `["user-follow", targetUserId]`，作用域回到缓存 key（`community-follow-button.tsx:9-24`）。
4. **首页 PostCard `useState(prop)` 不跟随异步 prop** → 固定点前已修为派生 + `override`（`community-home.tsx:363-370`），本批保留未回退。
5. **发布成功后列表缓存从未失效** → `usePublishPostMutation` 失效两个列表 key + 新帖互动状态（`community-queries.ts:159-170`），与文档论证逐条对应。
6. **H-2 的 `enabled` 静默失败** → `write-read-consistency.spec.ts:22-26` 的注释明确"帖子 id 用真实 UUID，否则批量链路静默不跑"，且断言了请求发生（`expect.poll(() => state[POST_ID].liked)`）。

### 1.3 Spec 轴的缺陷

**[S-1｜高] 学习文档声称 "mutation 从 1 处 → 10 处"，实际 7 个 hook（新增 6 个）**
Spec 原文（16 号文档 §一）："mutation 从 1 处 → 10 处"。
实测 `grep -n "useMutation({" front/src/lib/community-queries.ts` → 行 120/131/161/192/209/220/241 共 **7 处**（固定点为 1 处，净增 6）。docs/learning/README.md 的第 16 条摘要同样写 "1 处 → 10 处"。数字不实，读文档的人会高估覆盖面。

**[S-2｜高] "`await <api 写函数>` 直调归零"不成立：举报提交仍是裸 await**
Spec 原文（16 号文档 §一）："`await <api 写函数>` 直调归零"。
实测 `front/src/components/community/community-report-dialog.tsx:41` 仍是 `await createReport(targetType, targetId, reason)`，无 mutation、无 invalidate、无并发保护。审计 H-12 的未覆盖清单明确包含"举报提交（`community-report-dialog.tsx`）"，因此这既是缺口也是文档失实。

**[S-3｜中] 发评论 mutation 失败后，评论区的错误展示位不再显示错误（行为回归）**
改动前 `submitComment` catch 里同时 `setApiError(...)` 与 `notify(...)`；改动后只剩 `notify`（`community-post-detail.tsx:381-388`），而 `apiError` 仅由 `commentsQuery.isError` 驱动（`:377`）。结果：评论发布失败只在 toast 里闪现 2.6s，页面上原有的红色错误行永远不出现。无 spec 要求这个变化，属于未声明的行为回退。

**[S-4｜中] 首页 PostCard 的 `override` state 是残留的私有真相源，永不清除**
`community-home.tsx:367-369`：`{ postId, likeCount, ..., ...initialInteraction, ...override }`——`override` 永远压在从 prop 派生的基础值之上，且只有 mutation 成功时才写入、没有任何清除路径。invalidate 后 `feed-interaction-states` 重取拿到的新 `initialInteraction` 会被旧 `override` 盖掉：用户在另一个标签页点赞，本页卡片仍显示旧值。这正是 H-1 要消灭的"私有真相源"模式，只是从"每次挂载冻结"降级为"每次操作冻结"。16 号文档 §三.4 把 `useState(prop)` 判为 bug，但同一函数里保留了一个等效的 override 模式，没有论证。

**[S-5｜低] `useCommentsQuery` 未设 `retry: false`，与同文件纪律不一致**
`community-queries.ts:98-108` 无 `retry` 配置，走 react-query 默认重试 3 次。同文件的 `usePostInteractionsQuery:76` 与 `useSearchPostsQuery:55` 都显式 `retry: false` 并写了理由（"失败要立即冒泡给 UI"）。评论列表失败会白转 3 个重试周期才显示"评论加载失败"。审计 M-3 建议的全局 retry 策略（`community-provider.tsx:10` 仍只有 `staleTime` + `refetchOnWindowFocus`）本批未做，可以接受，但新加的 hook 应至少与邻居对齐。

**[S-6｜低] 发布 mutation 未失效 `["hot-topics"]`**
`usePublishPostMutation` 失效两个列表 key，但首页右栏热门话题的 key 是 `["hot-topics"]`（`community-home.tsx:650`）。发完帖回首页，右栏 top-5 话题不含新帖的标签，`staleTime` 120s 内不更新。文档只承诺"两个列表 key"，不算违背，但是推导失效集合时漏掉的一个读路径。

**[S-7｜低] 关注 mutation invalidate `["feed-interaction-states"]` 是无动作的**
`useFollowUserMutation` 的 onSuccess 同时 invalidate 了 `feed-interaction-states`（`community-queries.ts:213`），而该查询只返回 `liked/favorited`（`api.ts:354-362`），关注写操作不改变其内容。多打一次批量请求，无害但属噪音，说明失效集合是复制的而非推导的。

---

## 二、测试基线清洗：逐项核对

Spec 依据（17 号文档 §二 Result）："e2e **95 passed / 0 failed**（这套测试第一次真正零红灯）；后端全量 **168 测试零失败**；vitest 31 个单测；CI 前端 job 从 2 步扩到 4 步。"

### 2.1 已验证（本次实跑）

| Spec 声明 | 实测 | 结论 |
|---|---|---|
| e2e 95 passed / 0 failed | `npx playwright test` → **95 passed (30.8s)** | ✅ 属实 |
| 3 个 e2e 失败补 mock 修复 | 新增 `front/e2e/helpers/post-detail-mock.ts`（48 行，mock auth/me、csrf、posts/{id}、comments、batch-states），ui-regression 3 个用例接入（`:106,:155,:197`） | ✅ 属实，且 mock 集完整（含 CSRF 与登录态，不是只 mock 数据） |
| 18 处绝对 URL 改通配 | diff 中 18 处 `-page.route("http://localhost:8080` | ✅ 数量属实 |
| vitest 引入 + 31 单测 | `npx vitest run` → **4 文件 / 39 用例全绿**（31 + HEAD 上 `afe4e3a` 新增的 8 个 search-highlight） | ✅ 属实（增量为后续提交） |
| prettier `endOfLine` 改 auto | `.prettierrc.json` 由 `"lf"` → `"auto"` | ✅ 属实，与 core.autocrlf=true 的冲突解释合理 |
| CI 前端 job 扩步 | `.github/workflows/ci.yml` frontend：Lint → **Type check** → **Format check** → **Unit tests** → Build | ✅ 属实，且顺序在 build 之前（符合"先失败省时间"的论证） |
| SaToken 白名单补 batch-states | `SaTokenConfigure.java:63` 新增一条，注释写明"游客首屏就要拉，缺这条整个首页对未登录用户 401" | ✅ 属实，与 17 号文档 §三根因一致 |
| TwoLevelCache 7 处 warn 传异常对象 | diff 中 evict / publishEviction / tryLock / unlock / read / write / sentinel 共 **7 处** `e.getMessage()` → `e`，并移除消息末尾 `": {}"`；另在 `evict` 上方加 javadoc 说明"不要再把 getMessage 补回来" | ✅ 属实，javadoc 防回归的处理与文档一致 |
| 后端全量 168 测试零失败 | **未验证**（需 Docker Desktop + Testcontainers，本环境不具备） | ⚠️ 无法确认 |

### 2.2 Spec 轴的缺陷

**[S-8｜高] HEAD 上 `npm run format:check` 是红的——本批自己接进 CI 的步骤立刻失败**
实测（HEAD，未改一行代码）：

```
$ npm run format:check
[warn] src/components/community/search-highlight.tsx
[warn] Code style issues found in the above file.
$ echo $?  →  1
```

来源是 HEAD 提交 `afe4e3a`（"抽出 parseHighlightFragments 纯函数并补 8 个单测"）的 JSX 排版，其提交信息声称"验证：tsc 0 error、eslint 0 warning、vitest 39/39、search.spec.ts 7/7"——**唯独没有 format:check**。讽刺的是 `docs/agents/issue-tracker.md` 本批刚新增的验证分级条目写的正是"前端测试工具链改动 → `npm run test` + `npm run format:check` + `npx tsc --noEmit`"。修复是一行排版：`npm run format` 后该文件 diff 为 1 增 11 删，随后 tsc / eslint / vitest 39 / e2e 95 我均实跑复绿（本报告审查后已还原工作区，未代改）。
建议：补一个提交只做 `npm run format`，否则 CI 手动跑一次前端 job 就在 Format check 这一步红。

**[S-9｜低] "CI 前端 job 从 2 步扩到 4 步"与 17 号文档的提交范围不符（文档滞后于代码）**
`f9bd189`（17 号文档 Result 的收口提交）时 frontend job 是 Lint / Format check / Unit tests / Build = 4 步；其后的 `e9f6aba`（"补 tsc --noEmit 步骤——vitest 绿不等于类型对"）又加了 Type check，现为 **5 步**。docs/learning/README.md 的第 17 条摘要同样没提 tsc 步骤。文档与代码不同步，读者会以为类型检查没进 CI。

**[S-10｜低] 单测数量 "31" 未含 HEAD 新增的 8 个**
17 号文档与 README 都写 31，实际 39（`afe4e3a` 的 search-highlight 8 例）。31 是 `dd093ce` 时点的数，文档没错但已过期；与 S-9 同根：retro 写完后仍有提交，未回头更新。

---

## 三、范围声明的"明确不做"项：确认未做（符合声明）

1. **首页 Feed 未迁 react-query** — 确认。`community-home.tsx:482-576` 的 `Feed` 仍是 `useState×5 + useEffect + fetchPosts/fetchFollowingFeedByCursor`，降级语义（following 报错 / 其他 tab 降级 mock / `feedIssue` 401 分支）完整保留。16 号文档 §五与 17 号文档 §七都写明了"收益是少几行 useState，风险是弄坏降级路径"的判断，两轮结论一致。✅
2. **H-9 六页 mock 接真数据** — 确认未做。`echoes/tools/news/creations-page.tsx` 仍从 `@/lib/mock` 导入主数据。附带发现：**`characters-page.tsx` 在固定点前已接真 API**（`fetchCharacter/fetchCharacterPage`，PR #57），所以"H-9 六页"实际剩 5 页；范围声明沿用了审计时的旧计数。这不影响实现正确性，但后续引用 H-9 时数字应更正。

---

## 四、Scope creep（diff 中超出范围声明的新增）

| 项 | 位置 | 评估 |
|---|---|---|
| `scripts/push-health-check.sh`（40 行） | `e80fd82` | **范围外**。与 H-12 / 测试基线清洗均无关，是为解决"git push 假成功"临时加的运维脚本。且硬编码了绝对路径 `/g/kuros-web`、代理 `127.0.0.1:12450`、远端 `qingshuixiaohang/kuros`，换机即失效。提交信息本身解释了动机（可接受），但它属于流程工具，不应混在功能批次里。 |
| `search-highlight.tsx` 重构 + 8 单测 | `afe4e3a` | **范围外但有正当性**。XSS 关键路径的纯函数化 + TDD（RED→GREEN 有记录），质量好；对应审计 H-10 的邻域而非 H-12。代价是引入了 S-8（format:check 红）。 |
| 后端两项（白名单 + TwoLevelCache 日志） | `e30d1ea` / `7918802` | **不属 creep**。17 号文档 §三/§四把这两项列为测试基线清洗的产出（"后端全量跑出 2 个失败，查到底是鉴权白名单遗漏"），有 spec 依据。 |

其余 23 个提交全部落在 H-12、测试基线清洗、CI/docs 范围内，无越界。

---

## 五、汇总

### Spec 轴 findings（按严重度）

| # | 严重度 | 一句话 |
|---|---|---|
| S-8 | 高 | HEAD 上 `npm run format:check` exit 1——本批刚接进 CI 的步骤在最新提交上就是红的 |
| S-1 | 高 | 文档 "mutation 1 → 10 处"，实际 7 个 hook（净增 6） |
| S-2 | 高 | "写 API 直调归零"不成立：举报提交仍裸 `await createReport`，无 invalidate、无覆盖 |
| S-3 | 中 | 评论发布失败后，评论区原有错误展示位失效，只剩 toast |
| S-4 | 中 | PostCard `override` state 是未清除的私有真相源，压过 invalidate 后的新 prop |
| S-5 | 低 | `useCommentsQuery` 缺 `retry: false`，与同文件其余查询不一致 |
| S-6 | 低 | 发布 mutation 漏失效 `["hot-topics"]` |
| S-7 | 低 | 关注 mutation invalidate `feed-interaction-states` 是无动作请求 |
| S-9/S-10 | 低 | 两份 learning 文档滞后于后续提交（CI 步数、单测数） |

### 总体判断

**Spec 轴：主体达成，文档诚信度有亏。** 六类写操作中五类半（点赞/收藏/评论/关注/发布/举报处理）按设计落地，失效 key 与 queryKey 的推导在代码注释里有据可查，写后读一致性有 e2e 钉住（5 用例实跑通过）；测试基线清洗的每一项都可复现（e2e 95/95、vitest 39/39、mock helper、通配 URL、CI 三步、prettier 配置、后端两处修复）。主要问题集中在三处：**HEAD 状态 CI 自红（S-8）**、**两份 retro 文档的数字与范围声明和代码不符（S-1/S-2/S-9/S-10）**、以及**举报提交这一类写操作与"归零直调"的承诺有落差（S-2）**。明确不做的两项确认未做且理由充分。

### 建议的后续动作（按优先级）

1. `npm run format` 修 S-8，单独一个提交；推送后手动跑一次前端 CI job 确认 5 步全绿。
2. 把 `createReport` 接入 `useReportMutation`（invalidate `admin-reports`），补 1 个 e2e；顺带把 16 号文档的 "10 处" 改成实数。
3. 清掉 PostCard `override`：mutation 成功后不写本地覆盖，完全依赖 invalidate 重取（批量状态接口已返回 liked/favorited，足以驱动 UI）。
4. 评论失败恢复内联错误位（`mutation.isError` 也驱动 `apiError`）。
5. 17 号文档与 README 补 tsc 步骤与 39 单测；H-9 的"六页"更正为五页（characters 已接 API）。

### 本次审查实跑记录

| 命令 | 结果 |
|---|---|
| `npx tsc --noEmit`（front） | 0 error |
| `npx eslint`（front） | 0 error / 0 warning |
| `npx vitest run`（front） | 4 文件 / 39 用例全绿 |
| `npx playwright test`（front） | 95 passed / 0 failed |
| `npm run format:check`（front） | **exit 1**（`search-highlight.tsx`，见 S-8） |
| `npm run format` 后复跑 format:check | 通过（1 增 11 删），已还原 |
| 后端 `./mvnw test` | 未跑（需 Docker/Testcontainers，环境不具备） |

---

## 六、处置结果（leader 核实后）

Spec 轴报告返回后逐条复现，结论：**10 条 findings 全部属实**，其中 2 条是
审查对象自己造成的文档失实。处置如下：

| # | 严重度 | 核实 | 处置 | 提交 |
|---|---|---|---|---|
| S-8 | 高 | ✅ 复现：`npm run format:check` exit 1 | `npm run format`，diff 1 增 11 删 | `348003a` |
| S-1 | 高 | ✅ 实数 7 个 hook（我数错了 `useMutation(` 的出现次数） | 文档改为实数 + 勘误根因 | `beaf732` |
| S-2 | 高 | ✅ `createReport` 确为裸 await，无 invalidate | 补 `useCreateReportMutation`，invalidate `admin-reports` | `348003a` |
| S-3 | 中 | ✅ 内联错误位只剩 toast | `apiError` 改由 `commentsQuery.isError \|\| createMutation.isError` 驱动 | `348003a` |
| S-4 | 中 | ✅ `override` 无清除路径，压过新 prop | **整个删掉**，完全依赖 invalidate 重取 | `348003a` |
| S-5 | 低 | ✅ 与邻居不一致 | 补 `retry: false` + 理由注释 | `348003a` |
| S-6 | 低 | ✅ `hot-topics` 确被遗漏 | 补进失效集合 | `348003a` |
| S-7 | 低 | ✅ 该 key 内容不受关注影响 | 移除无效 invalidate + 注释说明推导原则 | `348003a` |
| S-9/S-10 | 低 | ✅ 文档滞后两个提交 | 39 例 / 5 步 / 六页→五页 | `beaf732` |

S-4 是最有价值的一条：`override` 正是我在 retro 里批判的「私有真相源」
模式，只是从「挂载时冻结」降级为「操作后冻结」——自己写的教训没套到
自己几小时后写的代码上。删掉后 e2e 95/95 仍全绿，证明 invalidate 重取
足以驱动 UI，本地第二份真相本来就是多余的。

**Standards 轴报告单独成文**（子代理独立交付）。
