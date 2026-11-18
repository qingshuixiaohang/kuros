# kuros-web Java 后端只读代码审计报告

| 项 | 内容 |
|---|---|
| 审计日期 | 2026-10-10 |
| 审计方式 | **纯只读静态审计**。未运行 `mvn`/`mvnw`、未启动 Docker/Testcontainers、未启动任何服务、未执行任何构建或部署。全部结论来自源码阅读 + 只读取证命令（`grep`/`grep -c`/`find`/`wc`/`git log`/`sed -n` 读取区间）。本次会话对仓库的**唯一写操作**是生成本报告文件。 |
| 审计范围 | `kuros-backend`（内容域）、`kuros-user`（用户域）、`kuros-gateway`（网关）三个 Maven 工程的 `src/main` + `src/test` 全部 Java 源码、全部 `db/migration` 迁移、全部 `application*.properties`、以及 `compose.yml` 中与三服务相关的段落（用于验证安全边界与运行拓扑）。**不含** `front/`（Next.js 前端）与 `scripts/`。 |
| 规模 | 主源码 **164 个 .java / 9177 行**（backend 120/7160、user 42/1934、gateway 2/83）；测试 **36 个 .java / 6271 行**（backend 28/5043、user 4/734、gateway 4/494）；资源文件 14 个（3 主 + 3 测试 application.properties、11 个 Flyway 迁移共 407 行 SQL）。 |

## 取证用的只读命令（摘要）

```
find <svc>/src -name '*.java' | wc -l            # 文件数
find <svc>/src -name '*.java' -exec cat {} + | wc -l   # 行数
grep -rn "@Transactional" --include=*.java ...   # 25 处，其中 readOnly=true 10 处
grep -rh "@Test" ... | wc -l                     # 183 个测试方法
grep -rhE "assertThat|assertTrue|...|andExpect"  # 583 处断言
grep -rln "Mockito" --include=*.java <tests>     # 仅 1 个文件（PostCdcHandlerTest）
grep -rn "catch (Exception ignored)..."          # 3 处吞异常
grep -rc "log.warn|log.info|log.debug|log.error" # 17 / 5 / 6 / **0**
grep -rn "/internal/" --include=*.java           # 内部 API 鉴权豁免位置
grep -rn "checkRole|checkPermission"             # 权限校验实际使用点
grep -n "password|secret" --include=*.properties # 凭据默认值
find . -name "V8__*"                             # 迁移编号跳号核查（无结果）
find . -name "logback*.xml"                      # 日志配置核查（无结果）
grep -rn "micrometer-tracing|brave|zipkin|otel" --include=pom.xml   # 链路追踪（无结果）
grep -rln "@DirtiesContext" ... | wc -l          # 17 个测试类
grep -rh 'h2Url("' ... | sort -u                 # 23 个不同 H2 库名
git log --oneline -8                             # 变更基线确认
```

> 说明：所有"未发现"类结论（无 V8 迁移、无 logback 配置、无 tracing 依赖、无网关限流）均由上述只读命令的**空结果**支撑，非推测。

---

# 高危（6 条）

## 并发与事务

### H1 缓存驱逐与回填写在事务边界之内，违反项目自己的 `afterCommit` 纪律
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/post/service/PostPublishingService.java:99-110`（update）、`:72-91`（publish）、`:112-125`（delete）、`kuros-backend/src/main/java/com/kuros/kurosbackend/comment/service/CommunityCommentService.java:93`（create）、`:111`（delete）

**问题**：`update()` 是 `@Transactional`，方法体内第 108 行 `twoLevelCache.evict(POST_DETAIL, postId)`、第 109 行 `postService.findPublishedById(...)`（内部回填 L1+L2）都在事务提交前执行。`delete()` 第 121 行同理。`publish()` 第 90 行在事务内就把新帖写进两级缓存。

**为什么是问题**：三条具体后果——
1. **回滚即永久陈旧**：`update()` 中 evict 已执行、随后 `findPublishedById` 已把**未提交**的新内容写入 L1/L2，若此刻事务回滚（如第 107 行 `replaceMedia` 之后的 flush 失败、DB 抖动），L2 里残留的是一条从未提交过的帖子内容，且旧内容已被 evict 掉，只能等 L1 10s / L2 60s 自然过期——期间所有节点读到幽灵数据。
2. **违反本项目已成文的纪律**：HANDOFF 规范第 4 条与 `AuthService.java:111-120` 都明确"Redis 是非事务资源，写入必须 `TransactionSynchronization.afterCommit()`"，但该纪律只被应用在 RBAC 同步一处，缓存侧全部违反。同一仓库里两条相反的标准，比没有标准更危险。
3. `delete()` 的 evict 在事务内：回滚后缓存已被清空但帖子并未删除，读路径 miss → 回源 DB 得到旧数据 → 重新回填，功能上可自愈，但白白制造一次跨节点失效广播。

**建议**：把 evict 与"读一次以回填"整体移入 `TransactionSynchronizationManager.registerSynchronization(... afterCommit ...)`；回滚时不注册、不驱逐。可参照 `AuthService.login` 已有的 afterCommit 写法，抽一个 `CacheInvalidationAfterCommit` 小工具复用。

### H2 `/internal/**` 完全免鉴权，且 compose 把 user 服务端口发布到宿主机
**位置**：`kuros-user/src/main/java/com/kuros/kurosuser/shared/config/SaTokenConfigure.java:61-64`（`notMatch("/internal/**")`）、`kuros-user/src/main/java/com/kuros/kurosuser/web/InternalUserController.java:35`、`compose.yml:263`（`"${USER_PORT:-8091}:8080"`）

**问题**：内部 API（`/internal/v1/users/batch|following|followers|follow-stats`）在 SaToken 与 CSRF 两条拦截链上都是裸的——无会话、无 token、无签名。同时 `--profile full` 下 user 服务的 8091 映射到宿主机。

**为什么是问题**：任何能访问宿主 8091 端口的进程（同机其他容器、CI runner、本机任意程序、内网扫描）都可以 `curl 'http://localhost:8091/internal/v1/users/batch?ids=<uuid>'` 批量拉取全站用户昵称/头像/简介，或遍历 `followers` 重建完整关注关系图。`InternalUserController.java:24-29` 与 `UserDirectoryClient.java:23-24` 的注释都承认这是"生产化前的显式让步"，但代码里没有任何一层防线，compose 的端口映射还把这条路铺到了宿主机上。对求职作品而言，这是一个面试官 10 秒就能复现的漏洞。

**建议**（任一即可，建议组合）：① full profile 下删除 `ports:` 映射或改为 `127.0.0.1:8091:8080`，内部调用只走 compose 网络名；② 加一个共享密钥头（`X-Internal-Token`，值来自 env），拦截器里校验；③ 网关侧不为 `/internal/**` 建路由（当前已满足，保持）。

### H3 dev 固定验证码 123456 是**代码默认值**，且默认回显——任意手机号可登录
**位置**：`kuros-user/src/main/resources/application.properties:68`（`app.auth.dev-code=${APP_AUTH_DEV_CODE:123456}`）、`:71`（`app.auth.dev-code-exposed=${APP_AUTH_DEV_CODE_EXPOSED:true}`）、`kuros-user/src/main/java/com/kuros/kurosuser/auth/RedisVerificationCodeService.java:63,77`、`kuros-user/src/main/java/com/kuros/kurosuser/service/AuthService.java:83`

**问题**：当 `APP_AUTH_DEV_CODE` 未注入时（裸跑、IDEA 直跑、任何忘配 env 的部署），验证码恒为 `123456`，且 `issueCode` 的响应体直接把 `devCode` 回显给调用方。同时 `AuthService.login` 是"验证码通过 → 找不到用户就 `createUser`"的隐式建号。

**为什么是问题**：三者叠加 = **未授权账号创建 + 任意手机号登录**。攻击者 POST `/api/v1/auth/code` 拿到响应里的 `123456`，再 POST `/api/v1/auth/login` 用任意手机号即可获得一个有效会话（该手机号若不是真实用户，就顺手创建一个）。`.env.production.example` 确实要求置空，但默认值在源码里、注释在 `:64-67` 也承认"IDEA 默认值"——防线依赖"部署的人记得看 example 文件"。另外 `RedisVerificationCodeService.java:66-75` 的 60s 频率限制在 dev 模式下被整段跳过，爆破无成本。

**建议**：默认值改为空（`app.auth.dev-code=`）与 `false`，dev 便利性完全交给 env/compose；在 `AuthService` 或一个 `@ConditionalOnProperty` 里对"dev 码非空 且 非 dev/test profile"做启动期 fail-fast，把隐患变成显式错误。

### H4 `StpUtil.login()` 在数据库事务内写 Redis 会话——回滚即产生孤儿会话
**位置**：`kuros-user/src/main/java/com/kuros/kurosuser/service/AuthService.java:94-125`（`@Transactional` 在 94，`StpUtil.login(user.getId())` 在 106，afterCommit 同步 RBAC 在 115-120）

**问题**：`login()` 是 `@Transactional`。第 101 行 `createUser(phone)` 建号、第 106 行 `StpUtil.login()` 立即把会话写进共享 Redis、第 109 行 `syncRoleField(user)` 落库。第 115-120 行特意用 `afterCommit` 延迟 `syncPermissionsToRedis`，注释写得很清楚："Redis 是非事务资源，DB 回滚会产生孤儿会话"——但 `StpUtil.login()` 这一步 Redis 写入就在事务内，且早于任何 afterCommit 保护。

**为什么是问题**：若 106 之后事务回滚（如 `syncRoleField` 的 `userRepository.save` 撞唯一键、连接池耗尽、flush 失败），Redis 里已有一个指向**从未提交用户**的有效会话。用户拿着 Cookie 能通过 `/auth/me`（走到 `userRepository.findById` 才 401），但 backend 侧的 `checkLogin` 直接放行——跨服务会话可见性把这个孤儿会话的爆炸半径放大到了整个 backend。这正是注释想避免的场景，只是漏了最关键的那一次写。

**建议**：把 `StpUtil.login()` 也移入 afterCommit（或改用 `TransactionSynchronization.afterCommit` 包裹"建会话 + 写权限缓存 + 设 Cookie"）；注意 SaToken 的 Cookie 写入需要请求上下文，afterCommit 时 `SaHolder` 仍可用（同事务内响应未提交）。

### H5 管理员确认举报后删除帖子/评论，却不驱逐 `postDetail` 两级缓存
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/report/service/ContentReportService.java:84-101`（handle）、`:114-124`（`applyDisposition`）

**问题**：`applyDisposition` 直接 `post.delete(...)`（:117）/ `comment.delete(...)`（:122）把目标置为 DELETED，全程没有调用 `twoLevelCache.evict(CacheNames.POST_DETAIL, ...)`。对比 `PostPublishingService.delete():121` 与 `CommunityCommentService.delete():111` 都做了驱逐。

**为什么是问题**：详情页的内容字段（含 `content` 正文、`media`）在 L1/L2 里最长存活 60s。管理员处理一条违规举报后，被处置的帖子在最多 60 秒内仍可通过 `GET /api/v1/posts/{id}` 完整读到正文与图片 URL——`findPublishedById` 走两级缓存，缓存里是删除前的内容。对 UGC 平台这是审核时效问题；同时它违反了本项目自己确立的"内容变更必须显式驱逐 L1+L2"的约定（`PostPublishingService.java:94-98` 的注释）。

**建议**：在 `applyDisposition` 的两个分支里补 `twoLevelCache.evict(CacheNames.POST_DETAIL, report.getTargetId())`；更根治的做法是把"删帖/删评论"收敛为领域服务方法（举报域不应直接改帖子域实体状态，见 M14），由该方法统一负责驱逐。

### H6 互动计数：Redis 是实时源、DB 是最终汇，但没有任何对账机制；两个关联 key 的 TTL 独立刷新会失配
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/interaction/redis/InteractionRedisStore.java:63-72`（readCount 从 DB 基线回填）、`:75-84`（readState）、`:103-105`（flip 分两次写 state 与 count）、`kuros-backend/src/main/java/com/kuros/kurosbackend/interaction/event/RocketMqInteractionEventPublisher.java:42-53`、`kuros-backend/src/main/resources/application.properties:106`（`app.interaction.redis.ttl-seconds=86400`）

**问题**：两条独立的失配路径——
1. **无对账**：写路径只保证"投递失败 → 同步降级落库"。若 MQ 投递成功但消费端 3 次重试后进 DLQ（`application.properties:86` `max-attempts=3`），DB 计数永久少一次；Redis 计数 24h TTL 到期后 `readCount` 用 DB 基线重播种，用户可见的点赞数**静默回退**。仓库里没有任何 DLQ 监控、补偿或对账任务。
2. **state key 与 count key 的 TTL 各自独立刷新**：`readState` 命中/回填只刷新 state key 的 TTL，`readCount`/`incrby` 只刷新 count key 的 TTL。两者中一个过期而另一个存活时，`flip()` 会用 DB 的 `existsById` 重新判定当前态：若 DB 关系行还没被消费端写到（异步在途），`readState` 回填 `false`，`flip` 认为"未赞→赞"从而**再 +1**，计数被双重计入；反向则可能把已存在的计数多减一次。

**为什么是问题**：这两个都是静默数据错误，不出错日志、不出异常，只在特定时序或消息丢失后显现，且 #11 是整个项目主打的可深挖难点——面试官问"Redis 计数和 DB 计数不一致怎么办"时，当前代码没有答案。

**建议**：① 加一个低频对账（定时或懒触发）：以 DB 为权威、校正 Redis，或至少记录偏差指标；② 把 state 与 count 合并到同一个 hash/一次 Lua 中，保证同生命周期；③ DLQ 加监控告警与手动重放入口。

---

# 中危（15 条）

## 异常处理

### M1 没有通用异常处理器，未映射异常会逃出统一响应信封；错误码还张冠李戴
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/web/ApiExceptionHandler.java:19-92`（无 `@ExceptionHandler(Exception.class)`）、`:61-65`（`ResourceNotFoundException` 硬编码 `"POST_NOT_FOUND"`）、`kuros-user/src/main/java/com/kuros/kurosuser/shared/web/ApiExceptionHandler.java:19-95`

**问题**：① 只覆盖 6 种特定异常。`DataIntegrityViolationException`、`LazyInitializationException`、`ObjectOptimisticLockingFailureException`、`HttpMessageNotReadableException`（JSON 格式错误）、`MethodArgumentNotValidException`、`MaxUploadSizeExceededException`（上传超限，见 M5 未配上限）全部落入 Spring 默认处理：返回 whitelabel 错误页或另一套 `{timestamp,status,error}` 结构，前端按 `code` 分支的逻辑直接失效。② `ResourceNotFoundException` 在 backend 被 3 个域复用（帖子 `CommunityCommentService.java:101`、评论、媒体 `DefaultMediaAssetService.java:113`、举报目标），但错误码恒为 `POST_NOT_FOUND`——删一条不存在的评论返回的 code 说的是"帖子不存在"。③ `AuthRequestException` 用 `Map.of()` 而其它 handler 用 `null` 作 `fieldErrors`，同一个字段两种序列化结果。④ 两服务异常体系已漂移：backend 有 `ForbiddenException`/`FileStorageException`，user 有 `UnauthorizedException` 且无对应物，`ErrorResponse.code` 的取值域没有统一文档。

**建议**：加 `@ExceptionHandler(Exception.class)` → 500 + `INTERNAL_ERROR`（并打 error 级日志，见 M11）；`ResourceNotFoundException` 增加 `code` 字段由抛出方给值；把 `fieldErrors` 统一为 `Map.of()`；把两服务的错误码清单收敛成一份文档（放 `docs/`）。

## 并发与事务

### M2 分布式锁没有续期（watchdog），TTL 硬编码 3 秒
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/lock/DistributedLock.java:61-66`、`kuros-user/src/main/java/com/kuros/kurosuser/service/UserFollowService.java:51`、`kuros-backend/src/main/java/com/kuros/kurosbackend/interaction/service/PostInteractionService.java:101`、`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/cache/TwoLevelCache.java:116,291`

**问题**：`tryLock` 只有 `SET NX EX`，没有看门狗续期、没有 `tryLock(timeout)`。所有锁 TTL 都是 3 秒。`TwoLevelCache` 的重建锁持锁时间 = `loader.get()` 的真实耗时（DB 慢查询、Feign 回填），完全可以超过 3s；`PostInteractionService.interact` 持锁时间 = 1 次 DB 查帖 + Redis 翻转 + 1 次 MQ 投递（或 1 个事务）+ 1 次 DB 读，MQ 投递慢时同样可能超 3s。

**为什么是问题**：锁过期后互斥失效，两个线程可同时进入临界区。具体后果：① 防击穿承诺破裂——两个线程都穿透 DB 重建；② 点赞互斥失效——两个线程都 `readState()` 读到 `false`、都判定"需要翻转"、都 `incrby 1`，Redis 计数虚增 2 而状态只有一份（这正是 `PostInteractionService.java:29-35` 注释声称"只串行化同一用户对同一帖"要防的事）。

**建议**：加一个带续期的实现（后台线程/Lua 续期脚本，或至少把 TTL 调到覆盖 p99 耗时并有监控）；`interact` 内把 MQ 投递移到锁外（只需保证"翻转 + 落库"在锁内），缩短临界区。

### M3 `PostInteractionService` 内部自调用 `@Transactional(readOnly=true)` 方法，代理被绕过
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/interaction/service/PostInteractionService.java:67`（`findPost` 标注 `@Transactional(readOnly=true)`）、`:103`、`:116`（从 `interact`/`like` 内直接 `this.findPost(...)`）、`:133`（`findPublishedPost` 无事务）

**问题**：`interact()` 是私有方法，内部通过自引用调用 public 的 `findPost()`，Spring AOP 代理不生效 → `findPost` 实际在**无事务**下运行。叠加 `application.properties:37` 的 `spring.jpa.open-in-view=false`，每个 repository 调用都是独立的 autocommit 会话。

**为什么是问题**：当前能工作纯属偶然——`CommunityPost` 没有在事务外访问 LAZY 关联（`tags` 是 `FetchType.LAZY`，`CommunityPost.java:72`）。任何人给 `toSummary`/`media()` 之路加一个懒加载字段访问，就会在锁内抛 `LazyInitializationException`，而它又落在 M1 说的"无通用异常处理"缺口上 → 500 + 非信封响应。同时无事务意味着读路径每次多几次连接获取/释放。

**建议**：把 `findPost` 的读逻辑抽到一个独立的 `@Transactional(readOnly=true)` 组件（或 `InteractionReadService`）注入调用，避免自调用；或在 `interact` 入口显式用 `TransactionTemplate` 包住读。

## 缓存

### M4 L2 key 无版本号、与业务 key 同命名空间平铺；L1 `type.cast` 未做降级
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/cache/TwoLevelCache.java:53`（`L2_KEY_PREFIX="cache:"`）、`:152`（`type.cast(wrapper.get())`）、`:371-373`（`l2Key`）、`kuros-backend/src/main/java/com/kuros/kurosbackend/feed/redis/FeedTimelineStore.java:31`（`KEY_PREFIX="feed:timeline:"`）

**问题**：① 所有 key（`cache:*`、`interaction:*`、`feed:timeline:*`、`sms:*`、`auth:*`、`lock:*`）共处 Redis db0，无统一前缀分层，`KEYS`/`SCAN` 运维与容量规划时分不清归属；`feed:timeline:{userId}` 的 value 结构（member=postId、score=epochMilli）没有版本号，将来若要改 score 语义只能全量清 key。② `get()` 的 L1 命中分支直接 `type.cast()`，若同一 `cacheName` 曾被不同类型写入（如缓存名复用、或单元测试串库），`ClassCastException` 会直接穿透缓存组件把请求打成 500——与 `readL2` 的"降级为 miss"容错纪律不一致。

**建议**：L2 key 加 schema 版本（`cache:v1:{name}:{key}`），结构变更时旧 key 自然失效；`type.cast` 包 try/catch 降级为 miss；给各 key 空间加统一前缀段（如 `kuros:cache:` / `kuros:feed:`）便于运维。

## SQL 与 Flyway

### M5 帖子列表存在 DB 层 N+1，且媒体排序匹配是 O(n²)
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/post/service/CommunityPostService.java:295`（`toSummary` → `media(post.getId())`）、`:330-345`（`media()` 两次查询 + 嵌套 stream filter）

**问题**：`findPublished`/`findPublishedByCursor`/`findPublishedByIds` 对**每一帖**调用 `media()`，而 `media()` 内部是 `postMediaRepository.findByPostIdOrderBySortOrderAsc(postId)` + `mediaAssetRepository.findAllById(...)` 两次查询。一页 50 条 → 1 + 50×2 = **101 条 SQL**。项目在 split-08 专门消除了跨服务 Feign 的 N+1（`CommunityPostService.java:92-94`），但 DB 层 N+1 原样存在。另外 `:341-342` 对每个 asset 都 `associations.stream().filter(...).findFirst().orElseThrow()` 全量扫一遍 associations，媒体数最多 9，尚可接受，但这是 O(n²) 写法且 `orElseThrow()` 在关联缺失时会抛 `NoSuchElementException`（又落到 M1 的缺口上）。

**为什么是问题**：#13 的定位是"读路径加固"，而列表读路径的单位请求 SQL 数是页大小的线性函数；压测叙事（`perf/ReadPathPerfBenchmark`）若不覆盖这一点，数据会失真。

**建议**：`media()` 改为批量版——先 `findByPostIdIn(ids)` 一次取回全部关联，再 `findAllById` 一次取资产，在内存里按 postId 分组；sortOrder 用一次遍历建 `assetId → sortOrder` 的 Map 替代嵌套 filter。

### M6 索引缺口：`comments.author_id` 无索引；`posts` 的 keyword LIKE 无法走索引
**位置**：`kuros-backend/src/main/resources/db/migration/V4__comments.sql:16-17`（只有 post_id 两个复合索引）、`kuros-backend/src/main/java/com/kuros/kurosbackend/post/repository/CommunityPostRepository.java:24-25`、`:51-52`、`:81-82`（`lower(p.title) like lower(concat('%',:keyword,'%'))`）

**问题**：① `ProfileService.findOwnComments`（`kuros-backend/.../user/service/ProfileService.java:145`）走 `commentRepository.findByAuthorId(userId, pageable)`，而 `comments` 表上没有 `(author_id, ...)` 索引 → 个人中心评论列表全表扫。② offset 与游标两条列表路径都带 `keyword` 时可走 `idx_posts_public_feed (status, published_at)`，但 `lower(title) LIKE '%kw%'` 是前导通配，索引只能用于 `status` 定位，随后逐行求值；ES 搜索（#14）已分担主检索，但 `/api/v1/posts?keyword=` 契约仍在，深翻页时退化明显。

**建议**：加迁移 `CREATE INDEX idx_comments_author_latest ON comments (author_id, status, created_at)`；keyword 筛选要么显式降级为"仅 ES 提供"（`/api/v1/posts` 拒绝该参数），要么加全文索引/生成列，至少在 ADR 里写清这是已知取舍。

### M7 V10 摘除 7 个外键后，没有任何孤儿数据检测或对账
**位置**：`kuros-backend/src/main/resources/db/migration/V10__drop_user_domain_tables.sql:7-13`

**问题**：`posts.author_id`、`comments.author_id`、`post_likes.user_id`、`post_favorites.user_id`、`content_reports.reporter_id/handled_by`、`media_assets.owner_id` 全部变成无约束列。注释说"用户存在性由 kuros-user 保证"，但：用户删除/封禁是用户域行为，backend 无感知、无事件、无清理任务；两个库之间也没有周期性对账。结果是内容域的 `author_id` 可以指向一个在 `kuros_user` 中不存在的用户，`UserDirectoryFacade.findAuthors` 会静默跳过它并渲染占位"用户"——一条数据错误被表现层吞掉，无从发现。

**建议**：至少加一个运维用的对账 SQL/脚本（backend 侧 distinct author_id 与 user 库 users 表做差集）纳入 CI 或巡检；中期用 RocketMQ 把"用户删除"事件广播到 backend 做级联处理。

## 测试质量

### M8 `FeedFanoutListener`（切片 #12 的核心写路径）零测试覆盖
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/feed/event/FeedFanoutListener.java:30-69`

**问题**：`grep -rn "FeedFanout\|PostPublishedEvent" --include=*.java kuros-backend/src/test` **无任何结果**。这个类是"发帖 → AFTER_COMMIT → Feign 取粉丝 → ZADD 扇出"的完整编排，包含三条可测行为：AFTER_COMMIT 时序（事务回滚不扇出）、排除作者自己、Feign/Redis 失败的 best-effort 降级。目前一条都没有。

**为什么是问题**：#12 的面试叙事（推拉混合、扇出、best-effort 语义）没有任何自动化证据；改错时序（比如误用 AFTER_COMMIT → 换成同步调用）不会被任何测试发现。

**建议**：补一个集成测试——用 `UserDirectoryStub` 当 kuros-user，验证：① 发帖后粉丝 timeline 含该帖、作者自己不含；② 桩置不健康时发帖仍 201 且不抛；③ 事务回滚时不扇出（直接触发一条会回滚的发布路径）。

### M9 弱断言与自证式测试：429 限流测试恒绿、删帖过滤测试零断言
**位置**：`kuros-backend/src/test/java/com/kuros/kurosbackend/ratelimit/RateLimitIntegrationTest.java:78-102`、`kuros-backend/src/test/java/com/kuros/kurosbackend/feed/FeedTimelineIntegrationTest.java:172-193`

**问题**：① `超过QPS阈值后返回429`：测试把阈值设成 100 却只发 20 个请求，方法体注释自己承认"20 个请求不太可能触发限流"。若真的触发才断言 `RATE_LIMITED`，不触发就**什么都不断言直接通过**——一个结构上恒绿的测试，比没有测试更糟（它给了"限流已覆盖"的假象）。② `findFollowingFeed_filtersOutDeletedPosts`：准备了帖子、软删除、调用服务，然后只有注释，**零断言**，同样恒绿。③ 全部测试都携带正确的 CSRF Cookie+Header，**没有任何一条负路径**验证"缺少 `X-XSRF-TOKEN` → 403"——CSRF 拦截器被删掉或改错，测试套件全绿。

**建议**：① 把阈值降到 1~2 或直接把请求数提到 > 阈值，让 429 必然发生并断言；② 给 `filtersOutDeletedPosts` 补 `assertThat(result.items()).isEmpty()`（或改为验证 `findPublishedByIds` 的状态过滤——注意当前实现不过滤 DELETED，见下）；③ 加 CSRF 缺失/不匹配的 403 用例与"登录后才能过 CSRF"的 401 用例。

> 附带发现：`CommunityPostService.findPublishedByIds`（`:266-277`）只 `filter(Objects::nonNull)`，**不过滤非 PUBLISHED 状态**。`FeedTimelineIntegrationTest.java:186-191` 的注释已承认这是已知局限（"实际效果：findPublishedByIds 会返回 DELETED 帖子"），但代码未修、也未在任何 issue/ADR 里登记。修复它同时能让 M9② 变成真断言。

### M10 测试基础设施成本畸高：23 个 H2 库、17 处 `@DirtiesContext`、每类独立 JVM
**位置**：`kuros-backend/pom.xml:311-316`（`reuseForks=false`）、`kuros-user/pom.xml:163-166`、各测试类 `TestDatabases.h2Url("<唯一名>")`

**问题**：为修跨类污染（cbae01c），采取了"每类唯一 H2 库名 + 每类独立 JVM"的组合拳。代价是 backend 28 个测试类 = 最多 28 次 Spring 上下文启动 + 28 次容器编排，`@DirtiesContext` 还有 17 处（其中 `KurosBackendApplicationTests` 单类内多处在方法级重建上下文）。`HANDOFF.md` 说"全量测试 45/45 绿"，但没提耗时；`ReadPathPerfBenchmark` 也被门控跳过。

**为什么是问题**：反馈周期长会直接压制测试纪律（开发者倾向于不加测试、或加恒绿测试——正是 M9 的温床）。且这套基础设施本身就是面试可深挖点，目前的代价没有被量化。

**建议**：量化并记录一次全量 `mvn test` 的耗时；用 `@DynamicPropertySource` + 共享上下文的方式减少唯一库名数量（真正需要隔离的是 Nacos/ES/cache.type 维度）；把 `@DirtiesContext` 从方法级降到类级或改用显式清理。

## 监控与日志

### M11 backend 的 Prometheus 端点未进 SaToken 白名单 → 指标采不动；无 tracing、无 logback、`log.error` 为 0
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/config/SaTokenConfigure.java:57-67`（只放行 `/actuator/health`）、对比 `kuros-user/src/main/java/com/kuros/kurosuser/shared/config/SaTokenConfigure.java:52-55`（放行 health + prometheus）、`kuros-backend/src/main/resources/application.properties:167`

**问题**：① backend 暴露了 `health,info,metrics,prometheus` 四个端点，但 SaToken 拦截器挂 `/**`，只有 `/actuator/health` 匿名可达 → `/actuator/prometheus` 要求登录会话，Prometheus scraper 无法采集 backend 指标。同一套约定在 user 服务是放行的，两服务行为不一致。② `grep -rn "micrometer-tracing|brave|zipkin|otel" --include=pom.xml` 无结果——跨服务调用（backend → user Feign）没有 trace 串联，一次慢请求无法定位是 Feign 还是 DB。③ `find . -name "logback*.xml"` 无结果——无日志格式/文件/级别治理，三服务日志形态不可比。④ `log.error` 出现 **0 次**（warn 17 / info 5 / debug 6）：所有降级与故障最高只到 warn，且大量 warn 只打 `e.toString()` 不含堆栈，出事故时无法定位根因。⑤ 业务指标只有 2 个（`posts.published.total` / `posts.publish.duration`，`MetricsConfig.java:29-45`），互动、搜索、缓存命中率/驱逐次数、锁竞争、扇出成功率全部无埋点。

**建议**：backend 白名单补 `/actuator/prometheus`（与 user 对齐）；引入 `micrometer-tracing-bridge-brave` 让 Feign/Redis/Jdbc 自动带 trace；加 logback 配置统一格式与级别；对"降级"类 warn 补全堆栈与计数器，把每次降级变成可观测事件。

## 安全

### M12 限流只在下游，网关层无任何限流；`/api/v1/search` 无独立规则
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/config/SentinelRateLimitInterceptor.java:62-82`、`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/config/SentinelRuleRefresher.java:49-56`、`kuros-gateway/pom.xml`（无 sentinel 依赖）、`kuros-gateway/src/main/java/com/kuros/kurosgateway/GatewayRoutesConfig.java:41-61`

**问题**：① Sentinel 拦截器只注册在 backend（`SaTokenConfigure.java:38-42`），user 服务与 gateway 都没有（user 是 Q15-A 的显式决策，gateway 无人决策）→ 网关作为唯一正门不设限，流量直达下游。② 资源名只有 `api-posts-list` / `api-post-detail` / `api-default` 三条 QPS 规则；`/api/v1/search`（ES 聚合查询，比帖子详情重得多）落在 `api-default`（默认 200 QPS），没有独立阈值。③ 无热点参数限流（同一 postId 被高频打）、无并发线程数限流、无自适应保护。④ `resolveResource` 用 `getRequestURI()`，不含查询串——游标翻页的深翻页攻击与普通列表同资源。

**建议**：网关层加 Sentinel/`RequestRateLimiter` 做第一道粗粒度限流；给 search 单独一条规则；对 `api-post-detail` 加热点参数限流（`paramIdx`）。

### M13 限流规则用 `loadRules` 整体替换，多来源规则会互相覆盖
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/config/SentinelRuleRefresher.java:49-57`

**问题**：`reload()` 每次 `FlowRuleManager.loadRules(rules)` 用全新的 3 条规则**整体替换**规则集。当前只有这一个来源所以无害，但一旦接入 Dashboard / Nacos 多来源，或将来要按运行时统计动态增删规则，就会互相覆盖且无告警。

**建议**：改为读取现有规则、按 resource 名 upsert；或在注释/ADR 里明确"单一来源"是不变量。

## 架构分层与依赖方向

### M14 `post` ↔ `interaction` 双向依赖；`report` 域直接写 `post`/`comment` 域实体（分层穿透）
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/post/service/CommunityPostService.java:22-23`（import `interaction.event.InteractionKind` / `interaction.redis.InteractionRedisStore`）、`kuros-backend/src/main/java/com/kuros/kurosbackend/interaction/service/InteractionProjectionService.java:10`（import `post.repository.CommunityPostRepository`）、`kuros-backend/src/main/java/com/kuros/kurosbackend/report/service/ContentReportService.java:36-37,114-124`

**问题**：① 包级循环：`post` 依赖 `interaction` 的 Redis 读源，`interaction` 依赖 `post` 的仓储与实体，两者互相 import。当前能编译，但任何要把这两个域拆成模块/服务的动作都会被这个环卡住。② `ContentReportService` 直接注入 `CommunityPostRepository` 与 `CommunityCommentRepository` 并修改其实体状态（`:117` `post.delete(...)`、`:122` `comment.delete(...)`），完全绕过了 `PostPublishingService`/`CommunityCommentService`——这正是 H5（不驱逐缓存）的根因，也意味着帖子域的写规则（缓存失效、事件发布、媒体状态）在举报路径上全部缺失。

**建议**：环的解法是把"计数读源"下沉到一个中立位置（如 `shared` 或一个 `interaction-read` 窄接口由 post 域依赖）；删帖/删评论改为调用帖子域/评论域暴露的领域服务方法，让缓存失效与事件发布只有一个入口。

### M15 跨服务共享代码"有纪律复制"已出现行为漂移
**位置**：`kuros-backend/src/main/java/com/kuros/kurosbackend/user/auth/StpInterfaceImpl.java:42-55`（Redis-only，缺失即空列表）与 `kuros-user/src/main/java/com/kuros/kurosuser/auth/StpInterfaceImpl.java:54-77`（Redis 缺失回源 DB 并回填）；`SaTokenConfigure` 两份的白名单条目（backend 6 条 vs user 2 条）

**问题**：HANDOFF 规范第 7 条明确"共享代码用有纪律复制，不建 common 模块（rule of three）"。目前 `DistributedLock`/`ApiResponse`/`ErrorResponse`/`CsrfInterceptor`/`SaTokenConfigure`/`StpInterfaceImpl` 各两份。`StpInterfaceImpl` 的**行为已经不一致**：backend 侧 Redis 未命中 → 返回空列表（403），user 侧 → 回源 DB 并写缓存。同一份 `auth:roles:{userId}` 契约由 user 写、backend 读，但两侧对"缓存缺失"的解释相反。这类漂移没有测试守护（没有任何测试对比两份实现的行为一致性）。

**建议**：给共享契约写"契约测试"（同一组输入在两个服务上产生相同输出的断言，或至少一个文档化的行为矩阵）；或者接受三次原则，把 `DistributedLock` 这类真正稳定的基建抽成内部 artifact——注意别过早建 common 模块，先让第三份复制出现。

---

# 低危（10 条）

### L1 Flyway 迁移编号跳号（缺 V8），且未记录原因
`find . -name "V8__*"` 无结果；backend 迁移为 V1-V7、V9、V10。Flyway 本身允许跳号，但 `HANDOFF.md`/`docs/adr` 未解释 V8 去向，后来者无法判断是漏文件还是有意跳过。建议在 `docs/adr` 或迁移目录 README 记一句。

### L2 登出吞掉一切异常；验证码校验 GET+DELETE 非原子
`kuros-user/src/main/java/com/kuros/kurosuser/service/AuthService.java:154-164`：`logout()` 的 `catch (Exception ignored)` 会连 `StpUtil.logout()` 的失败一起吞掉，且 Redis 删除权限缓存失败时无感知 → 登出后 `auth:permissions:` 残留（当前无 TTL，见 M15）。`RedisVerificationCodeService.java:99-108`：`get` 后 `delete` 两步，同一验证码的并发登录请求可能双双通过（重放窗口）。建议登出分类型处理、至少记 warn；验证码校验改用 Lua 原子 GET+DEL。

### L3 CSRF Cookie 未设 `Secure`/`SameSite`，令牌不轮换，写响应失败被吞
`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/config/CsrfInterceptor.java:62-71`（cookie 只设 path/HttpOnly）、`:55`（`catch (Exception ignored) {}`）。XSRF-TOKEN 是会话级不轮换的随机值；`Secure` 未设意味着 HTTP 部署下明文传输；`getWriter().write` 失败被静默吞掉（客户端收到空 body）。建议显式 `setSecure(true)`（生产）+ `setSameSite("Lax")`，写失败记日志。

### L4 compose 默认注入 dev 验证码 123456，且冒烟脚本硬编码
`compose.yml:262`（`APP_AUTH_DEV_CODE: ${APP_AUTH_DEV_CODE:-123456}`）。full profile 起的容器默认就是固定验证码。与 H3 叠加后，只要有人用 `--profile full` 起一套忘了改 env 的环境，就是可登录的。建议默认值置空，冒烟脚本显式传。

### L5 `springdoc` 默认开启，Swagger 白名单在生产同样生效
`kuros-backend/src/main/resources/application.properties:164-165`（`springdoc.api-docs.enabled=${SPRINGDOC_ENABLED:true}`）+ `SaTokenConfigure.java:65-66`（`/swagger-ui/**`、`/v3/api-docs/**` 匿名放行）。注释自己写了"生产环境必须关闭（安全风险）"，但默认是开、且白名单与 profile 无关。建议把 Swagger 白名单收敛到 `@Profile("!production")` 或与 `SPRINGDOC_ENABLED` 联动。

### L6 直连调试端口 8090/8091 默认开放
`compose.yml:263`（user）、backend 同款映射 8090。两个调试口绕过网关，且 user 的 8091 上跑着免鉴权的 `/internal/**`（H2）。生产 profile 应关闭这些映射。

### L7 游标是 base64 而非加密，跨排序模式复用仅靠 token 数校验
`kuros-backend/src/main/java/com/kuros/kurosbackend/shared/api/CursorCodec.java:22`（注释已承认"只是编码不是加密"）、`kuros-backend/src/main/java/com/kuros/kurosbackend/search/SearchSort.java:71-92`。客户端可解码/篡改游标内容；`hot`(4 token) 与 `latest`(2 token) 靠数量区分，但 `latest` 与 `relevance` 都是 2 token，跨 sort 复用会把 Float 当 Long 解析（恰好抛 400，属侥幸安全）。建议在游标里编码 sort 模式并校验。

### L8 全量重建同步执行于请求线程，且一次性载入全表
`kuros-backend/src/main/java/com/kuros/kurosbackend/search/PostIndexManager.java:84-99` + `PostIndexService.java:86-99` + `CommunityPostRepository.java:119-120`（`findAllWithTags()`）。`POST /api/v1/admin/search/reindex` 由管理员同步触发，遍历全表 + bulk + 切别名 + 删旧索引全在同一个请求线程里，数据量大时请求超时、内存峰值高。建议异步化（返回任务 id + 轮询）或分批。

### L9 媒体上传无 `@Transactional`，存储与元数据不一致无补偿
`kuros-backend/src/main/java/com/kuros/kurosbackend/media/storage/DefaultMediaAssetService.java:63-104`：`storageStrategy.put(...)` 成功但 `mediaAssetRepository.save(...)` 失败时，对象存储里留下无元数据的孤儿文件；反向（先存库后传文件）则有元数据无文件。`cleanupTemporaryAssets`（`:133-141`）逐文件删除、无批量。建议把"传文件 → 存库"包进事务 + afterCommit 传文件，或至少让孤儿文件可被清理任务发现（按 storage_key 反查 media_assets）。

### L10 MinIO 策略在构造函数里建 bucket，存储抖动直接阻断启动
`kuros-backend/src/main/java/com/kuros/kurosbackend/media/storage/MinIOStorageStrategy.java:61,132-144`：`ensureBucketExists()` 在构造期执行，MinIO 不可达时抛 `FileStorageException` → 应用启动失败。这与项目对 ES/RocketMQ 确立的"软依赖、不阻断启动"原则不一致。建议改为 `ApplicationReadyEvent` 后懒建 + 失败仅告警。

---

# Top 5 风险

| # | 风险 | 位置 | 影响 |
|---|---|---|---|
| 1 | 任意手机号可登录（dev 固定码 123456 是代码默认值 + 默认回显 + 隐式建号 + dev 模式跳过频率限制） | `kuros-user/.../resources/application.properties:68,71`；`RedisVerificationCodeService.java:63,77`；`AuthService.java:83,101` | 认证被完全绕过，可批量创建账号；生产环境一旦忘配 env 即全线失守 |
| 2 | `/internal/**` 零鉴权且 user 服务 8091 映射到宿主机 | `kuros-user/.../shared/config/SaTokenConfigure.java:63`；`InternalUserController.java:35`；`compose.yml:263` | 同机/内网任意进程可批量导出全站用户资料与完整关注关系图 |
| 3 | 缓存驱逐/回填在事务边界内；删帖路径（举报处置）干脆不驱逐 | `PostPublishingService.java:99-110`；`CommunityCommentService.java:93,111`；`ContentReportService.java:114-124` | 事务回滚 → L2 残留未提交内容且旧内容已失效（最长 60s 幽灵数据）；管理员处置违规内容后 60s 内仍可完整读取 |
| 4 | `StpUtil.login()` 在 DB 事务内写 Redis 会话 | `kuros-user/.../service/AuthService.java:94-125` | 事务回滚 → 孤儿会话驻留共享 Redis，且因"拆服务不拆会话"可被 backend 直接放行 |
| 5 | 互动计数无 Redis↔DB 对账；state/count 两 key TTL 独立刷新 | `InteractionRedisStore.java:63-105`；`RocketMqInteractionEventPublisher.java:42-53`；`application.properties:106` | MQ 进 DLQ 或时序竞态 → 计数静默虚增/回退，无日志无告警；主打难点在面试追问下无答案 |

---

# 值得保留的好实践

1. **分布式锁的释放用 UUID + Lua 原子校验**（`DistributedLock.java:37-46,75-77`），并在注释里写清"为什么不用 RedLock / 为什么不用 GET+compare+DEL"——取舍可见。
2. **锁-事务时序被显式修正过**：`UserFollowService.java:76-95` 用 `TransactionTemplate` 在锁内开事务并提交，确保 `commit` 先于 `unlock`，注释完整记录了竞态窗口与修复路径（HANDOFF 陷阱 #7）。
3. **`afterCommit` 纪律至少被正确应用一次**：`AuthService.java:111-120` 把 RBAC 同步延迟到事务提交后，并写明了"为什么"——这是 H1/H4 应对照的正面样板。
4. **两级缓存的完整三防 + 全链路降级**：`TwoLevelCache.java` 的 L1→L2→DB 三级读流、互斥重建锁、`__NULL__` 空值哨兵、以及"任何一环故障都退回直查 DB、绝不阻断读"的容错纪律（`:41-42,337-369`）。
5. **游标翻页贯穿三个存储**：MySQL keyset（`CommunityPostRepository.java:35-101`，含 hot 四元组的 OR 链展开）、Redis ZSet 游标（`FeedTimelineStore.java:96-147`，同分用 postId 字典序兜底全序）、ES `search_after`（`SearchQueryService.java:38-40,128-133`），统一用"多取一条"探测 hasMore 而不查总数。
6. **CDC 的"回源组装 + `_id=postId` 覆盖写幂等"**：`PostIndexService.java:25-31,60-73` 把 binlog 只当触发信号、以 DB 当前态重组文档，天然抵御重复投递；`SearchIndexManager` 用版本索引 + 别名原子切换实现零停机重建（`:77-99`）。
7. **ES 被当成真正的软依赖**：查询失败显式转 503 `SERVICE_UNAVAILABLE` 而非静默空结果或 500（`SearchQueryService.java:88-95`），`management.health.elasticsearch.enabled=false`（`application.properties:52`）避免拖垮健康门控，并有 `SearchUnavailableIntegrationTest` 固化该语义。
8. **`PostCdcHandler` 被设计成可单测的纯逻辑翻译层**，是全仓库唯一使用 Mockito 的地方（`PostCdcHandlerTest.java:30-163`）——mock 用在"隔离重依赖"上而非"伪造被测逻辑"，8 个用例全部断言外部可观测行为（触发了哪些 index/delete）。
9. **测试隔离的基础设施被认真对待**：`TestDatabases` 唯一 H2 库名 + `reuseForks=false`（`kuros-backend/pom.xml:305-316`），并记录了"单类绿、全量红"的根因；`CacheBreakdownIntegrationTest` 用真实 16 线程惊群验证"DB 只重建一次"。
10. **用 `@ConditionalOnProperty` 门控一切重依赖**：`InteractionStreamConfig.java:17-24`、`PostCdcStreamConfig.java:17-24`、`CacheConfig.java:38-41`、`SentinelConfig.java:23`——让主测试套件在无 broker/无 ES 的环境下确定性运行，而不是留给 CI 兜底。
11. **`CaffeineCacheManager` 静态模式的陷阱被显式记录**（`CacheConfig.java:49-51`）：新增缓存名必须在此登记，否则该名字退化为"仅 L2"——这类"下一个改动会踩的坑"被前置写清。
12. **降级策略按调用面语义分化并被测试固定**：`UserDirectoryFacade` 对"列表 vs 资料页"给出相反的降级路线（占位 200 / 503），`KurosBackendApplicationTests.java:264-290` 两条降级用例把该契约编码化。
13. **`spring.jpa.hibernate.ddl-auto=validate` + Flyway**：实体与迁移互相校验， schema 漂移会在启动期暴露而非运行期。
14. **`ApiResponse`/`ErrorResponse` 的 `@JsonInclude(NON_NULL)` 与游标契约的配合**（`ApiResponse.java:7`、`FeedController.java:50-51`）：用"meta 是否存在"让前端区分无限滚动与页码两种契约，零破坏演进。
