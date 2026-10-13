# 16 · 前端 mutation 层全量化：从 1 处到 10 处

> 批次：前端审计 E / H-12
> 提交范围：`dfd7b23` → `dd093ce`（7 个提交）

## 一、这件事在做什么

前端审计列出 52 条发现，其中 **H-1「零 mutation 层」**是根因型的一条：全仓只有 1 处 `useMutation`，其余写操作全是「await API + setState」。

这个模式的病灶不是"写法不优雅"，而是**每处手写 setState 都在造一个私有真相源**。点赞改本地的、收藏改本地的、评论 unshift 本地的、举报 map 本地的——它们各自为政，没有一层能发现彼此不一致。

## 二、STAR 叙事

**Situation**：用户在首页点赞，进详情页显示未赞；在详情页收藏，回首页显示未收藏。同一个用户在两个页面上看到两个状态，且**没有任何一层会报错**。

**Task**：把六类写操作（点赞/收藏/评论/关注/发布/举报处理）统一纳入 react-query 的 mutation 层，让写操作有单一的失效路径。

**Action**：每类写操作配一对 hook——读走 `useQuery`（key 按资源命名），写走 `useMutation`（`onSuccess` invalidate 同一个 key）。关键判断是**哪些 key 该被失效**：点赞收藏失效互动状态 + 三个列表 key；发布失效两个列表 key + 新帖的互动状态；举报失效带过滤条件的管理端 key。

**Result**：mutation 从 1 处 → 10 处，`await <api 写函数>` 直调归零。写后读一致性有了 e2e 钉住（`write-read-consistency.spec.ts` 5 用例）。

## 三、六个真 bug（按价值排序）

### 1. 评论本地 unshift，排序是错的

`submitComment` 把返回的评论插到数组最前。但评论区有"最热/最新"两种排序——**本地插最前在"最热"下位置就是错的**，新评论赞数 0，服务端未必排它第一。`totalItems` 也只是本地 +1，翻页口径跟着漂。

> 教训：**列表的排序和分页归服务端**。本地插入只在"按时间倒序且在第一页"这一种情况下正确，而它恰好是最常被测试的那种情况。

### 2. 举报处理完还留在待处理队列

`process()` 成功后本地 `map` 把 status 改成 `CONFIRMED`，但当前过滤 tab 是 `PENDING`——**那条记录还留在列表里**。用户看到"已确认"混在待处理中，而服务端已经把它过滤掉了。

> 教训：**本地改状态 + 服务端过滤 = 两个真相源**。带过滤条件的列表，写成功后必须重取，不能本地改。

### 3. 关注按钮只更新自己这一个实例

`setFollow(result)` 只更新当前 React 实例。帖子卡片上有关注按钮，作者主页也有——**它们是两个实例**，在卡片点关注，主页那个还是"＋关注"。

> 教训：invalidate 的作用域是**缓存 key**，不是组件。同一资源的多个 UI 入口共享一个 key，才能一起更新。

### 4. 首页 PostCard 用 useState 存异步 prop

```ts
const [interaction, setInteraction] = useState<PostInteraction | null>(initialInteraction ? {...} : null);
```

批量互动状态是异步到达的，prop 从 `null` 变成 `{liked:true}` 时 **`useState` 的初始值不会重新求值**——卡片永远停在挂载那一刻的状态。这是"首页显示未赞"的直接原因。

> 教训：**`useState(prop)` 只在挂载时读一次 prop**。需要跟随 prop 变化的值，要么派生（`const x = f(prop)`），要么用 `key` 强制重挂载。这条是 React 的高频面试题，也是真实生产 bug。

### 5. H-2 自己的验收测试被 UUID 约束挡着

`home-feed-request-count.spec.ts` 用 `n-plus-one-0` 这种非 UUID id，而 Feed 靠 UUID 正则识别"这是后端帖子"，批量查询的 `enabled` 也要求 UUID——**请求一次都没发生，测试却显示通过**。

> 教训：**`enabled` 条件里的静默失败最危险**。查询不跑不是报错，是"什么都没有"。给带 `enabled` 的查询写测试，必须断言请求真的发生了。

### 6. 发布成功后列表缓存从未失效

`community-posts` / `search-posts` 这两个 key 从没被任何 mutation invalidate 过。发完帖回首页，**新帖在 staleTime 内不出现**；搜索也搜不到刚发的内容。

> 教训：**失效集合要按"这个写操作会影响哪些读"推导**，不是按"当前页需要什么"。导航型写操作（发完就跳走）尤其容易漏——当前页看不到后果，问题在下一页。

## 四、环境与工具坑

### Playwright 复用旧 dev server

`reuseExistingServer: true`，改了源码但测试跑的是**旧 bundle**。排查"console.log 不打印"浪费了好几轮——先确认 server 是新的，再怀疑代码。

### `page.request.get` 不走 `page.route`

`page.request` 是独立的 APIRequestContext，不经浏览器，所以 `page.route` 的 mock **拦不到它**，请求会落到真实 dev server 拿 404。要在页面内用 `fetch` 才能验证 mock 状态。

### 两个 mutation 不能塞进同一个联合变量

```ts
const mutation = kind === "like" ? likeMutation : favoriteMutation;  // TS 推断成 never
void mutation.mutate(next as "like").then(...)  // .then 取不到
```

分开 `await`，不要硬转类型。

### 首页收藏按钮没有 `is-bookmarked` class

`isBookmarked` 只用于 `aria-label`。按 class 选择器写断言会永远找不到元素——**先看 DOM 再写选择器**。

## 五、没做的事（以及为什么）

**首页 Feed 没迁到 react-query**。它有复杂的降级语义：following tab 走 Redis ZSet cursor 分页，recommend/latest 走 posts 接口，失败时 following 报错、其他 tab 降级到 mock 数据。直接迁 infiniteQuery 会丢掉这套逻辑，而它是全仓最脆弱的组件且无测试护航。

判断标准：**改造的收益是"少几行 useState"，风险是"弄坏降级路径"**，不成比例。mutation 层的 invalidate 已经能作用到它依赖的批量状态查询，主要矛盾解决了。

**H-9 六页 mock 接真数据没做**。后端只有帖子域端点，news/echoes/creations/tools 四页需要先建新端点——那是后端任务，不是前端重构能单独推进的。

## 六、面试可讲的点

**Q：你怎么保证前端写操作后数据一致？**

分三层：单一真相源（服务端）、失效路径显式化（每个 mutation 的 `onSuccess` 列出受影响的 key）、回归防线（e2e mock 服务端维护真相，断言重取后的结果）。

**Q：`useState(prop)` 有什么问题？**

只在挂载时读一次。异步到达的 prop 变化不会被接住。这是我在批量互动状态接入时踩到的真 bug——首页卡片永远显示挂载时的旧赞态。

**Q：为什么评论不乐观更新？**

乐观更新要处理回滚（失败时恢复原值），复杂度高。而且评论的排序归服务端，乐观插入的位置本来就是猜的。这里选择"提交中禁用 + 成功后重取"。
