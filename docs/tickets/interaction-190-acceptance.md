# 互动故障与并发验收（#190）

## 已有可执行基础

在 `kuros-backend` 执行 `mvn test -Dtest=InteractionRocketMQIntegrationTest -Dkuros.it.rocketmq=true`。测试使用独立的 MySQL 8、Redis、RocketMQ nameserver 和 broker 容器以及 Flyway 种子数据；每次创建新容器，Broker 需要宿主机 10909、10911 端口空闲。测试入口是当前服务接口，不是认证 HTTP 接口。测试启动应用前显式创建互动 Topic；Broker 采用异步刷盘，不能证明同步刷盘的持久化确认承诺。

正常点赞和取消在 MySQL 查询关系及计数，并等待每一步实际完成；多用户并发提交后检查各关系存在且计数增量等于用户数。它们不证明积压时的顺序、操作 ID 幂等或故障恢复。

## 等待 #185、#188、#189 集成

以下场景尚未实现或验证，需以新操作接口、操作查询及可控故障注入接缝接入；不能把现有 H2 或模拟发布器测试当作通过证据。所有故障操作仅对测试容器执行。

| 场景 | 注入与通过条件 |
| --- | --- |
| 消费暂停 | 暂停消费者，提交操作确认受理且数据库未变；恢复后查询操作已应用，关系和计数正确。 |
| Broker 停机 | 停测试 Broker 后发送，明确拒绝时 MySQL 和 Redis 快照不变；结果未知不得断言拒绝。 |
| 响应丢失、重复与旧消息 | 在 Broker 确认后丢弃响应，重用操作 ID；重复和旧消息重放后只生效一次，旧点赞不能覆盖已完成取消。 |
| 消费者崩溃 | 分别在 DB 提交前、提交后失效前、失效后确认前中断进程；恢复后检查操作状态、关系、计数、缓存及消息重投。 |
| Redis 长时间故障 | 停 Redis 跨越重试窗口；业务提交保持 APPLIED，恢复后缓存失效或持久恢复路径可追踪。 |
| MySQL 死锁 | 两事务通过屏障制造锁竞争；验证受害事务完整回滚、有限重试及最终关系和计数，不能仅模拟异常。 |
| 查询鉴权 | 未知操作返回未知；不同用户不可读取操作结果。 |
| 前端 | 跨 Feed、详情、个人中心切换；延迟旧查询响应并切换账号，确认待确认状态隔离且权威结果不被覆盖。 |

还需覆盖 A→B→C 排队顺序、C 明确拒绝、无路由、失败事件重放、重试上限和恢复。具体注入点应由集成后的公开契约及容器控制确定，避免依赖业务内部私有方法。

## 2026-11-27 隔离容器补充验收（基线 c1fa96f）

运行：`cd kuros-backend && mvn test -Dtest=InteractionRocketMQIntegrationTest -Dkuros.it.rocketmq=true`。最终版本 13 项通过（0 失败、0 跳过）。每次使用测试独立 MySQL、Redis 和 RocketMQ；固定占用宿主 10909/10911。测试不调用认证 HTTP 接口，Broker 为异步刷盘。

- 停止测试 Broker 后提交返回 `INTERACTION_NOT_ACCEPTED`，关联 MySQL 关系、计数和 Redis 状态及计数快照未变化；仅对明确拒绝做不变断言，未知结果不等同拒绝。停机用例放最后，避免重启期间影响其他用例。
- 真正投递相同消息，以及点赞、取消完成后重投旧点赞；操作只生效一次，最终关系和计数保持取消。Broker 确认后在发布器返回边界模拟结果丢失，重用操作 ID 再投递，最终只增加一次。不是网络层丢包实验。
- 暂停 Redis 容器后经真实 Broker 投递：MySQL 提交成功且 `cache_pending=true`；恢复 Redis 后主动执行恢复任务，待失效标记清除并读到正确状态。此实验未跨完整重试窗口；不是 Redis 进程重启实验。曾尝试停止再启动容器，Lettuce 持续报 `RedisSystemException: Connection reset`，恢复等待超时；原因未确定，不能据此归因于业务。
- 通过测试替身在事务内计数更新前抛错，检查事务回滚后重试成功；另在提交后缓存失效前抛错，检查已提交和待恢复标记，再重放恢复。两者是异常注入，未杀死消费者进程，也未验证失效后确认前进程崩溃。
- `cd front && npx playwright test e2e/write-read-consistency.spec.ts --project=chromium`：9 项通过，覆盖 Feed、详情、个人中心、待确认、刷新及账号切换；HTTP 响应由测试模拟，不能作为真实前后端联调证据。首次启动因 Next.js 安全升级提示退出，重试成功；该提示建议升级 Next.js。

当时尚未验证真实消费者进程在三个位置崩溃、Redis 故障跨重试窗口、真实 MySQL 死锁、HTTP 受理响应网络丢失、无路由及真实前后端跨页面联调。真实页面联调需隔离启动 gateway、user、backend、Nacos 等服务并建立测试会话；本次尚未完成该环境，不应视为通过。

## Redis 重启端口诊断（2026-11-27）

使用独立 `redis:7-alpine` 容器和 Docker 随机端口执行 stop/start：6379 的宿主映射从 32942 变为 32943，重启后容器内 `redis-cli ping` 返回 PONG。测试通过 `redis.getMappedPort(6379)` 在 Spring 上下文启动时配置端口，停止并重启容器后既有 Lettuce 配置仍指向旧端口。这是此前重启恢复失败的可复现环境因素，但尚未抓包证明此前 reset 的来源。后续重启实验应使用稳定宿主端口或容器网络别名，并重新验证客户端重连；暂停容器不验证进程重启。

本次诊断未完成完整重试窗口、真实消费者进程崩溃、真实 MySQL 死锁或真实前后端联调，不能将这些场景标记为通过。

## 2026-11-27 Redis 停止与重启验证

`InteractionRocketMQIntegrationTest#redis停止跨恢复周期后重启并恢复缓存` 使用宿主固定端口 16379（须空闲），真实停止并重启测试 Redis；独立 MySQL 和 RocketMQ 容器保持运行。单项测试通过：停机期间观察到两次定时恢复调用失败，操作仍为 APPLIED、关系与计数正确且 `cache_pending=true`；重启后原 Lettuce 连接恢复，定时恢复任务自动清除标记，读取状态正确。此前随机宿主端口在容器重启时改变；本测试避免这一问题。

消费者发现数据库操作已提交时会确认消息，因此 Redis 故障不进入消费者三次失败暂停路径；这里跨越的是两次默认 30 秒的缓存恢复周期，不是消息重试耗尽。最终自动恢复版本单项测试通过；手动恢复版本的完整容器套件 14 项通过。

## 2026-11-27 真实 MySQL 死锁验收

`InteractionMySqlIntegrationTest#真实死锁牺牲业务事务后完整回滚并可重投` 用两个真实事务在 InnoDB 上形成环：

- 业务事务（`projection.apply` 取消点赞）先 `select ... from posts for update`，再删除 `post_likes` 关系行；
- 竞争事务先对同一关系行执行 `select ... for update` 持有 `X,REC_NOT_GAP` 记录锁，等业务事务在删除上进入 `LOCK_STATUS = 'WAITING'` 后，再反序请求 `posts` 行锁；
- InnoDB 立即检测死锁并回滚业务事务；竞争事务随后获得 posts 锁并提交。

为让牺牲者确定为业务事务，竞争事务在同一事务内先插入 16 行测试表（仅增加 undo 记录，不参与业务断言），
InnoDB 回滚 undo 更少的一方。测试使用真实 MySQL 8 容器（REPEATABLE READ），不依赖异常替身或模拟发布器。

通过条件（本次实测通过）：业务事务抛出 `ConcurrencyFailureException`（消息含 deadlock）；
`interaction_operations`、`post_likes` 关系行和 `posts.like_count` 均完整回滚；同一事件重投后只应用一次，
关系被删除且计数回到基线。

局限：这是可控死锁，不是生产自然出现的竞争；测试运行在单 JVM，未覆盖消费者进程崩溃与消息层重投。
测试表 `interaction_deadlock_probe` 仅用于偏置牺牲者选择，结束时删除。

## 2026-11-27 缓存失效/回填竞态与版本围栏

此前 `docs/interaction-reliability.md` 记录的「提交前读旧值、提交失效后写回」竞态已由版本键围栏关闭：
读路径先取版本、再读数据库，Lua 在版本未变时写入；失效先递增版本再删除缓存（含状态、计数、窗口、对账）。

新增并实测通过（`InteractionMySqlIntegrationTest`，真实 MySQL 8 + Redis 7 容器）：

- `收藏状态旧快照不能覆盖并发失效`：读取者拿到旧状态后，业务插入关系并失效；回填被版本拒绝，重读后写入新状态。
- `收藏计数旧快照不能覆盖并发失效`：读取者拿到旧计数后，业务更新计数并失效；回填被拒后重读新值并写入。
- `对账旧真相不能覆盖并发失效`：对账读到旧真相后发生失效；脚本返回未提交，重读新真相后只写入一次。

同一围栏覆盖既有的点赞计数与近期窗口用例。版本键语义：点赞计数/窗口版本不设 TTL；收藏计数版本不设 TTL；
状态版本在失效时按状态 TTL 过期。仍保留的正常可见窗口是「读取在提交前已经返回旧值」，不属于回填污染。

## 2026-11-27 版本围栏回归与 A→B→C

- `InteractionMySqlIntegrationTest`（真实 MySQL 8 + Redis 7）9 项通过，含真实死锁与三个版本围栏用例。
- 受 Redis 版本键改动影响的三组套件与读取路由单测共 43 项通过：
  `InteractionAsyncIntegrationTest` 18、`InteractionBatchStateIntegrationTest` 13、
  `InteractionCountReconcileIntegrationTest` 10、`RedisPostInteractionCountReaderTest` 2。
- `InteractionRocketMQIntegrationTest`（真实 Broker）14 项通过。
  `同一帖点赞取消再点赞最终只增加一次` 现在保留 A、B、C 三个操作编号并断言全部 `APPLIED`，
  覆盖排队顺序下的「不丢弃」：若取消 B 丢失，计数会多 1；若乱序把取消排到最后，终态会变成未点赞。

回归同时暴露并修复了 `0c9ddcd` 引入的两处缺陷：

- `CommunityPostRepository.readCommittedLikeCount` 用 `REQUIRES_NEW` 读已提交快照，发帖事务内回读详情时看不到未提交的新帖，标量查询返回 null，
  基本类型代理抛 `AopInvocationException`（发布接口在点赞计数缓存冷启动时 500）。改为 `coalesce(sum(...), 0)`：
  无已提交行时返回 0，符合「已提交快照中的点赞数」语义。`TwoLevelCacheIntegrationTest` 3 项因此恢复通过。
- 点赞计数键从文档契约 `interaction:count:like:{postId}`（整数字符串）被改为 `count:patten:post:{postId}` 并写成 `"基线,0"` 复合值，
  第二个字段没有读取方，且使外部 INCR 失效。已恢复文档契约与整数值，相关套件的键断言同步更新。

## 2026-11-28 进程崩溃、无路由与真实全栈联调

### 真实消费者进程在三个边界崩溃

`InteractionRocketMQIntegrationTest#消费者进程在三个边界崩溃后均可恢复`（`@Order(91)`）把消费者放进独立 JVM：
`com.kuros.crashharness.InteractionCrashHarness` 启动完整 Spring 上下文并读取父测试的 MySQL/Redis/RocketMQ 容器地址，
到达指定阶段时先写标记文件，再 `Runtime.halt(86)`（不执行关闭钩子、不给事务提交机会）。父 JVM 的消费者在用例期间关闭，
每个边界崩溃后启动一个正常消费子进程等待 Broker 重投。

- 提交前崩溃：事务未提交，操作、关系行和计数都不存在；重投后只应用一次，缓存读回已赞。
- 提交后、失效前崩溃：操作已提交且 `cache_pending=true`，旧 Redis 状态仍在；重投清除标记且不重复计数。
- 失效后、确认前崩溃：缓存已删除、消息未确认；重投幂等，不重复计数。

崩溃子进程按操作编号过滤（`-Dcrash.operation`）：Broker 可能先重投上一条尚未确认的消息，未过滤时子进程会提前在
错误消息上崩溃，该现象已在首轮失败中观察到。Windows 上完整测试 classpath 超过 32K 命令行上限，子进程通过 Java
`@argfile` 接收 classpath；引号内反斜杠是转义符，路径改用 `/`。

整类 17 项通过（`Tests run: 17, Failures: 0, Errors: 0`，409.4 秒），含新增的无路由与 Broker 清理用例。

### Topic 无路由

`无路由的Topic被明确拒绝而不是结果未知`：真实 Broker 正常运行、`autoCreateTopicEnable=false`，向不存在的
`interaction-topic-without-route` 发布返回 `REJECTED`。普通 `send` 的无路由是 `responseCode=10005`
（`NOT_FOUND_TOPIC_EXCEPTION`）；选择队列的 `send` 在 RocketMQ 5.3.1 抛 `responseCode=-1` 且消息以
`No route info for this topic` 开头，发布器按两种形状都归类为明确拒绝，单测覆盖后者。

### Broker 侧清理已受理未消费消息

`已受理消息被Broker清理后不会从绑定表恢复`（`@Order(90)`）：A 持续失败进入 `PAUSED`，同队列 B 已受理但未消费；
删除并重建 Topic、`resetOffsetByTime ... -s now`、重建消费者后，B 的 ConsumeQueue 条目真实消失。A 从 MySQL
失败记录恢复为 `APPLIED`，B 保持 `UNKNOWN`，C 经真实 Broker 到新消费者证明链路仍可用。

局限：用「删除并重建 Topic」代替等待 Broker 保留期计时器，未验证 CommitLog 滚动与保留文件清理本身；结论与
`docs/interaction-reliability.md` 的边界一致——绑定表不能重建已丢失消息。

### 真实前后端跨页面联调

新增 `front/e2e/interaction-real-stack.spec.ts`（默认跳过，`KUROS_REAL_STACK=1` 才运行；全文件无 `page.route`）。
使用真实 MySQL/Redis/Nacos/RocketMQ、backend:8090、user:8091、gateway:8080 与前端 dev server:3000，通过真实登录
（dev 固定码 123456）建立会话。流程：账号 A 首页点赞 → 详情读回已赞且计数 +1 → 详情收藏 → 个人中心「收藏」出现该帖 →
账号 B 登录看到未赞未藏且计数包含 A 的赞 → 切回 A 取消点赞，首页与详情回到基线。

实测：首次通过 16.3 秒；修复收藏确认语义后 `--workers=1 --repeat-each=2` 两次串行通过（17.2、14.4 秒）；
修复计数映射后同一命令再次两次通过（16.1、14.4 秒）。真实栈用例写共享账号与计数，必须单 worker 运行；
默认 `--repeat-each` 会启动多个 worker，并发登录同一手机号会互相消费验证码（已复现，不是产品缺陷）。

联调发现并修复了一个真实产品缺陷：收藏写接口只保证「已受理」，投影由 MQ 异步落库；原 `useFavoriteMutation` 在写请求
返回后立即 invalidate，重取可能跑在消费者前面。失败轨迹中的权威读响应为 `favoriteCount=0` 但 `favorited=true`，
界面停在「已藏」直到下一次导航。修复：`useFavoriteMutation` 在写被受理后轮询
`GET /api/v1/posts/{id}/interactions`，确认关系态到达目标值后再 invalidate；10 秒未确认按失败提示，不把未确认写当成功。
点赞已有 LikeCoordinator 轮询 operation，收藏由此补齐同一确认语义。

联调还暴露并修复了两处既有显示缺陷（非本分支引入，本次一并处理）：

- 首页卡片此前用 `Number("8.6k")` 解析展示缩写，结果为 0（`community-feed.tsx` 局部 `parseLikeCount`）。现在
  `toGuide` / `searchItemToGuide` 透传原始 `likeCount`（`Guide.likeCount`），列表和详情优先用它；缺失时用新增的
  `parsePostCount` 反解（支持 `k` / `w` / `万`，无法识别返回 0）。`community-feed.tsx` 与
  `community-post-detail.tsx` 收敛到同一个解析函数。单测覆盖各量级与「12345 → "1.2w" → 12000」的近似边界；
  真实联调在点赞前断言首页展示计数等于详情基线。
- 390px 视口下页面 `scrollWidth=412`：旧规则只隐藏 `.game-switcher` 内层，`.game-selector` 包裹层仍占
  32px + margin + flex gap。`globals.css` 在 `max-width: 620px` 下隐藏整个 `.game-selector`，个人中心移动端
  用例由失败转通过。

### 前端回归基线

全量 Playwright（`npx playwright test --workers=1`）：164 passed / 0 failed / 1 skipped（跳过的是
`interaction-real-stack` 的默认门禁）。此前遗留的 8 项失败均已清理，原因都是测试断言与当前实现脱节，不是产品缺陷：

- `community-shell`：桌面三栏已按库街区节奏固定为 240 + 12 + 640 + 12 + 304，右栏宽度断言从 300px 更新为 304px。
- `creations`：预览图使用 `unoptimized` 的 next/image，`src` 直接是原始路径，不再断言 `/_next/image?url=` 查询参数。
- `post-detail`：`/art/` 轮播图同时渲染背景层与主图两个 `img`，断言收窄到 `.community-carousel-image-primary`，
  并等待接口封面替换演示封面。
- `following-feed`：关注流已改为登录后请求，补 mock `/api/v1/auth/me` 建立登录态；登录门文案更新为
  「跟上你关注的创作者 / 登录后继续」，新增「登录态失效（feed 401）回到登录门」用例；未登录用例断言不会
  向关注流接口发请求。

Vitest 58 项、`tsc`、`eslint`、`prettier` 均通过；`community-carousel.tsx` 一并按仓库 Prettier 规则格式化。

### 仍未覆盖的边界

- Broker 保留期计时器与 CommitLog 清理本身未等待实测，用 Topic 删除/重建代替。
- Broker 为异步刷盘；单 Broker，无副本容灾；未验证同步刷盘承诺。
- 真实联调用 dev 固定验证码与共享种子账号，不代表生产短信/认证链路；单 gateway 实例。
- 自然生产竞争、跨多实例消费、网络层丢包仍未覆盖；MySQL 死锁为可控构造。
