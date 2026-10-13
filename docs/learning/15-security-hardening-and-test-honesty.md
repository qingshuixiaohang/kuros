# 安全加固 + 测试诚实性 + 读路径性能（无编号轮次）

> 本轮不是功能切片，而是对切片 #1-#14 存量代码的一次**质量收口**：安全缺口、事务边界、
> 读路径 N+1、以及最关键的——**把「恒绿的假测试」改成真测试**。
>
> 与前面 14 个切分的差别：前面是「从无到有建能力」，本轮是「验证已有能力真的成立」。
> 结果是抓到了 3 个被假测试掩盖的真实缺陷。

## 1. 本轮全景

分支 `fix/batch-a-security`，18 个提交，四条线：

| 线 | 提交 | 解决什么 |
|---|---|---|
| 安全 | `08cbcc2` `6217634` `96dfd7a` `3c1d4ac` | dev 固定验证码可登录任意号；`/internal/**` 零鉴权可导出全站用户；事务回滚残留孤儿会话；违规内容缓存 60s 仍可读 |
| 事务边界 | `74fc17b` `a3db5a5` | 缓存驱逐写在事务内，回滚后 L2 残留幽灵数据 |
| 性能 | `bb2f7d6` `f3de4dc` `d92dd00` | 分布式锁无续期；列表读路径 DB 层 N+1；comments 无 author_id 索引 |
| **测试诚实性** | `756de96` `3ebd702` `59a0510` `1077996` `d97a012` `68a03de` | 见第 3 节——本轮最高价值的部分 |

外加 `ae14976`：timeline member 带作者前缀 + 按作者批量清理，取关残留有解。

## 2. 关键难点解析

### 2.1 「改后绿」不够，必须「改前红」

这是本轮最重要的纪律。缓存失效延迟到提交后（B1）那类改动，很容易写出一个**永远绿**的测试——
因为它断言的是"提交后缓存被驱逐"，而正常路径本来就会驱逐。

真正能证明修复有效的，是构造一次**回滚**，然后断言 Redis 里没有残留。这也是 A3/A4/B1 三个
回归测试的共同结构：用 `@MockitoSpyBean` 让某一步抛异常，验证事务回滚后外部状态干净。

**推论**：一个测试如果只能被"正确的实现"通过，而不能被"删除这段实现"弄红，它就没有价值。
本轮专门做了一次反向验证（见 2.5）。

### 2.2 分布式锁续期：static 脚本实例是个坑

`DistributedLock` 原有 `tryLock/unlock`，但没有续期。临界区真实耗时不短——
`PostInteractionService.interact` 锁内要做「1 次 DB 读 + Redis 翻转 + 1 次 MQ 投递」，
MQ 慢时超过调用方硬编码的 3 秒 TTL，互斥失效。

加了 `renew()` 后测试**立刻失败**：持锁 2.5 秒后锁值读不到。定位手法是加一个
"每次新建脚本对象"的诊断用例作对照——它通过而封装失败，差异才浮出来：

> `static` 的 `DefaultRedisScript` 实例在本项目配置下（StringRedisTemplate + 默认
> 序列化器），复用同一实例的第二次起 `execute` 返回 null。

修复为每次调用本地构造脚本对象。**这个坑在 `InteractionRedisStore.reconcileCount` 里
被显式避开了**（注释里写了"不复用 B2 踩过的坑"）。

**教训**：Redis Lua 脚本对象不要图省事做成 static 常量。

### 2.3 对账的真相源必须是幂等可重算的那一份

互动计数有三份状态：Redis 实时计数、`posts.like_count` 冗余列、`post_likes` 关系表。
MQ 投递失败有同步降级兜底，但降级也失败时事件永久丢失——而 Redis key 过期后会按
"可能已经错了的"冗余列回填，**把偏差固化成真相**。

对账时选关系表 `COUNT(*)` 作真相，而不是读冗余列：冗余列是增量维护的产物，事件丢失
会让它永久偏移；关系表行数幂等，重复消费不会让行数变化。

对账本身也用 Lua 原子完成"比对+修正"：分步执行时正常写路径的 `flip` 可能插进来，
对账会用旧真相覆盖掉一次合法增量——**为了修偏差反而制造偏差**。

### 2.4 软删帖泄漏：一个错误假设被写在注释里供起来

`findPublishedByIds` 名字承诺 Published，实际只 `filter(Objects::nonNull)`。
原测试的注释写着：

> "findAllById 能找到 DELETED 状态的帖子（JPA 不自动过滤）……这是当前实现的已知局限"

也就是说**软删帖漏进关注流这件事是已知的，但测试选择了不验证**。补上断言后立刻变红。

`timeline` ZSet 里的 postId 不随软删除消失（删帖只改 status），所以用户会在关注流
看到已删除内容。修复：按 `PUBLISHED` 过滤，影响 `FeedService` 两个方法 +
`ProfileService` 收藏列表三处。

**教训**：注释里出现"已知局限"时要警觉——那往往是一个没写断言的 bug，不是设计决策。

### 2.5 反向验证：故意弄坏生产代码，确认测试真的会红

改完 429 限流测试后，做了一次破坏性验证：把拦截器改成永远放行，3 个用例立刻红
（"正常请求不限流"仍绿，符合预期）；恢复后 4/4 绿，生产代码零改动。

同样手法用在扇出翻页：先把实现改回"只取第一页"，2 个翻页用例立刻失败——**静默丢人的
实证**。

这是对抗"假绿"最直接的手段：**如果不知道测试为什么能失败，就不知道它为什么算通过。**

## 3. 测试诚实性：本轮最高价值的部分

原有测试套件里有两个"名字承诺行为、实际零断言"的用例：

| 原用例 | 假在哪 |
|---|---|
| `RateLimitIntegrationTest.超过QPS阈值后返回429` | 阈值 100 却只发 20 请求，方法体内一个断言都没有，靠"如果碰巧 429 了就顺带检查响应体"蒙混 |
| `FeedTimelineIntegrationTest.findFollowingFeed_filtersOutDeletedPosts` | 准备了帖子、软删除、调用服务，然后只有注释、零断言 |

改造成 4 + 1 个真断言后，后者**当场暴露了 2.4 的真 bug**。

改造 429 测试时还挖出两重隐藏的假绿来源：

1. `application-test.properties` 里 `app.sentinel.enabled=false`——删掉原
   `@TestPropertySource` 后拦截器根本不注册，所有用例以 200 通过。
2. Sentinel 的 QPS 是进程内秒级滑动窗口，跨测试方法累积：前一个用例打满后，
   同一秒内下一个用例的第一个请求被误判超限。

## 4. 简历 STAR 写法

**S（情境）**：一个 Spring Cloud 微服务项目（Nacos/Gateway/Sentinel + MySQL/Redis/
RocketMQ/ES）的存量代码，测试套件表面全绿，但存在两个"名字承诺行为、实际零断言"的用例。

**T（任务）**：在不改动功能的前提下收口质量——补安全缺口、修事务边界、消除读路径 N+1，
并验证已有能力真的成立。

**A（行动）**：
- 按"改前红、改后绿"纪律逐个修复，每个修复配一个能因删除实现而失败的回归测试
- 用 Hibernate Statistics 量化读路径：一页 50 帖的 SQL 从 102 条降到 3 条
  （媒体批量装载 + `left join fetch` 消除 tags 懒加载）
- 起临时 MySQL 容器实测索引收益：`comments.author_id` 从全表扫 19995 行降到 ref 命中 10 行
- 把两个恒绿假测试改造成真断言，过程中抓到 3 个被掩盖的真 bug（软删帖泄漏、
  锁续期失效、扇出静默丢人）
- 对破坏性验证：故意弄坏生产代码确认测试会红，再恢复

**R（结果）**：18 个提交，SQL 数降 97%、索引扫描行数降 99.95%，3 个潜伏 bug 被发现并修复；
测试从"能通过"变成"能失败"——后者才是回归防线的定义。

## 5. 原理详解

### 5.1 为什么缓存失效必须在事务提交后

`twoLevelCache.evict()` 写在 `@Transactional` 方法体内时，事务回滚已发生但缓存已驱逐：
旧内容没了、新内容从未提交，只能等 TTL 过期。更糟的是回填（`findPublishedById` 内部
回填 L1+L2）会把未提交数据写进缓存。

解法是 `TransactionSynchronizationManager.isSynchronizationActive()` 判断：有事务则注册
`afterCommit`，无事务同步执行。返回值仍从事内读取（语义等价，注释说明）。

### 5.2 为什么登录态写入也要 afterCommit

`StpUtil.login()` 在事务内立即把会话写进共享 Redis。若其后 `syncRoleField` 的 save 撞
唯一键导致回滚，Redis 里残留指向从未提交用户的有效会话——用户拿 Cookie 过 `/auth/me`
（走到 findById 才 401），但 backend 侧 checkLogin 直接放行。

token 用 request 作用域 ThreadLocal 带出，`finally` 清理，避免线程池复用串号。

### 5.3 为什么扇出必须翻页取全

`fetchFollowerIds` 只请求第一页（size=5000）不看 `totalPages`，粉丝超 5000 的大 V 会
丢掉第 5001 个以后的粉丝——那些人的 timeline 永远看不到新帖，且无任何日志。

终止条件用 `totalPages` 而非"本页是否满"：最后一页可能刚好不满，用页大小判断会多发
一次无用请求。专门写了一个 total 是页大小整数倍的用例防那种写法。

## 6. 面试八股文整理

**Q：怎么保证缓存和数据库的一致性？**
分两层。第一层是**写入时机**：缓存失效必须延迟到事务提交后（`afterCommit`），否则回滚后
L2 残留从未提交的幽灵数据。第二层是**对账兜底**：像互动计数这种三份状态的，要有以幂等
可重算数据（关系表 COUNT）为真相的对账路径，且对账本身要原子（Lua），否则修偏差时会
覆盖掉正常写路径的合法增量。

**Q：Redis 分布式锁怎么保证临界区慢的时候不失效？**
SET NX EX 只有 TTL 没有续期，业务慢于 TTL 就丢互斥。加看门狗定时 renew，续期也用 Lua
保证"校验持有者 + EXPIRE"原子。注意 renew 间隔取 TTL/3，留两次失败余量。本项目踩过的坑：
Redis 脚本对象不要做 static 常量，复用实例的第二次起 execute 会返回 null。

**Q：怎么发现和量化 N+1？**
用 Hibernate Statistics 在测试里开统计（不动生产配置），直接读
`getPrepareStatementCount()`。本项目实测一页 50 帖 102 条 SQL，定位到两层：媒体每帖两次
查询（改批量 `findByPostIdIn` + `findAllById`）+ tags 是 LAZY ManyToMany 而原查询的
`left join` 只是过滤用不填充集合（改 `left join fetch` + `distinct`）。降到 3 条。
测试契约写成"整页 SQL ≤ 10"，既抓 O(n) 回归又留余量。

**Q：索引怎么设计，怎么验证收益？**
看查询的 WHERE + ORDER BY。`findByAuthorId` 带 `ORDER BY created_at DESC`，所以建
`(author_id, created_at)` 而不是单列——排序键进索引，MySQL 可反向扫索引省掉 filesort。
验证方式是起临时容器灌数据对比执行计划：`type=ALL, rows=19995, Using filesort` →
`type=ref, rows=10, Backward index scan`。

**Q：你怎么知道一个测试是有效的？**
看它能不能被弄红。本轮专门做破坏性验证：把限流拦截器改成永远放行，3 个用例立刻红；
把扇出实现改回只取第一页，2 个翻页用例立刻失败。恢复后全绿、生产代码零改动。
**如果不知道测试为什么能失败，就不知道它为什么算通过。**

## 7. 技术选型对比

| 决策 | 选择 | 放弃的方案 | 理由 |
|---|---|---|---|
| 对账真相源 | 关系表 `COUNT(*)` | 读 `posts.like_count` 冗余列 | 冗余列是增量维护，事件丢失永久偏移；关系表行数幂等可重算 |
| 对账原子性 | Lua 脚本 | GET 比较后 SET | 分步执行时正常写路径的 flip 会插进来，用旧真相覆盖合法增量 |
| 续期脚本对象 | 每次本地构造 | `static` 常量 | static 实例复用导致 execute 返回 null（本轮实测踩坑） |
| 扇出翻页终止 | `totalPages` | 本页是否满 | 最后一页可能不满；且服务端给出的页数比客户端推断可靠 |
| 媒体批量装载 | 两次查询 + 内存分组 | `@EntityGraph` / JOIN FETCH 一次拿全 | 媒体关联与资产是两张表，JOIN FETCH 会产生 Cartesian 积；两次查询 + 分组 SQL 数同为常数 |
| FeedFanoutListener 测试 | 纯 Mockito 单元测试 | `@SpringBootTest` | 被测逻辑是翻页取粉丝，不依赖 Redis/DB；Spring Boot 4 已移除 `@MockitoBean`，无轻量 bean 替换手段。1 秒 vs 16 秒 |
| PR #57 合并时机 | 暂不合并，等用户确认 | 直接合入 main | 与 `fix/batch-a-security` 同改 `characters-page.tsx`，且改 main 需用户授权 |

## 8. 遗留与后续

- **未合并**：PR #57（角色图鉴接真实 API，`codex/issue-56-character-catalog`），状态
  MERGEABLE/CLEAN，本地验证后端 36/36 + 前端 tsc/lint 0 error + 角色图鉴 e2e 2/2。
  与 `fix/batch-a-security` 同改 `characters-page.tsx`（PR 117+/94-，本轮 10+/1-），
  合并需用户确认策略。
- **前端剩余审计**（`.scratch/audit-frontend.md`，52 条）：H-1/H-3 引入 mutation 层 +
  invalidation（动 9 处写操作）；H-2 首页 Feed 每卡一次 `/interactions` 的 N+1；
  H-9 六个页面仍以 mock 为主数据源（需后端先有端点）；M-9 无 prettier（47 行超 400 字符，
  最长 2032）。
- **既有测试失败**（非本轮引入，已用 `git stash` 复现确认）：`ui-regression.spec.ts` 三个
  `/guides` 评论按钮与表情浮层用例。
- **待办**：B3 对账目前只有 API 与单帖路径，缺定时任务/全量扫描入口；`FeedFanoutListener`
  的"推拉混合"（大 V 不推、读时拉取合并）仍留 #13。
