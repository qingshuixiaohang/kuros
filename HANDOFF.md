# 项目交接文档（2026-09-21）

## 当前状态

**已完成切片 #1-#9**（每份复盘见 `docs/learning/`）：

| # | 主题 | 核心产出 |
|---|---|---|
| 1 | 分布式会话与鉴权（SaToken + Redis + RBAC） | 替代 Spring Security，Cookie 会话，四表 RBAC |
| 2 | MinIO 对象存储 + 策略模式 | `StorageStrategy` 双实现（Local / MinIO） |
| 3 | Caffeine 本地缓存 | 热点读加速 + 失效策略 |
| 4 | Sentinel 限流 | 拦截器 + 规则动态刷新 |
| 5 | Redis 分布式锁 | SET NX EX + UUID 锁值 + Lua 原子释放（已修锁-事务竞态） |
| 6 | OpenAPI 文档 | springdoc 接入 |
| 7 | Actuator + Micrometer + Prometheus | 指标暴露（需显式加 registry 依赖） |
| 8 | Nacos 注册与配置中心 | 动态配置 + 服务发现 |
| 9 | Gateway 统一入口 | DSL 路由 + 鉴权前置 + 全局过滤器 |

**进行中：切片 #10 服务拆分**（Issue #67，分支 `codex/issue-67-service-split`）：
- 已完成：spec + ADR 0003 + 9 个工单 + **Phase A 四批重排全部提交**（split-01~04：shared / user / post+comment / interaction+report+media 归位，纯移动零行为变化）+ **全量测试 45/45 绿**（cbae01c 修复跨类污染，见"常见陷阱 11"）
- 已完成：**split-05 `kuros-user` 工程骨架**（7f61947）：独立库 `kuros_user` + Flyway V1/V2（种子与 backend 逐字一致，user1=ADMIN）+ compose `user` 服务（宿主 8091）+ CI 第 5 job + backend job 扩全量；集成测试 4/4 绿（Nacos 注册 + health UP + prometheus 200 + 迁移种子）
- 已完成：**split-06 认证链路迁移**：登录/会话/RBAC 查询迁入 kuros-user（AuthController/AuthService/验证码三件套/StpInterfaceImpl/CommunityUser + RBAC 仓储）；backend 移除认证代码、刻意保留 SaToken/CSRF 放行条目（直连 auth 落到"无 handler"→404，新测试编码化）；网关新增 `kuros-user-auth` 路由（`order(-1)` 显式优先 + 声明在 `/**` 之前双保障，双桩集成测试证命中）；compose user 服务补 `APP_AUTH_*`、gateway 挂 user 依赖；冒烟/会话脚本改用双库共有种子号（窗口期限制见"常见陷阱 13"）。CI 五 job 全绿（PR #68 首轮 run 35587464883，含 deployment 全栈冒烟 4m1s）；AI 侧快验：三工程 test-compile + compose config + node --check 通过
- 已完成：**split-07 关注迁移 + 内部 API + 数据清理**（f9fae25）：关注链路（UserFollow 实体/仓储/Service/Controller，分布式锁与幂等语义逐字保持）与内部 API（`/internal/v1/users`：batch 简档/follow-stats/following/followers）迁入 kuros-user；backend 删用户域 16 文件（含 UserSession 死代码）、资料/个人中心整端 503 降级、作者占位"未知漂泊者"保留 authorId、StpInterfaceImpl 改 Redis-only；V10 先摘 7 FK 再 DROP 7 张用户域表；网关新增 `kuros-user-follow` 路由（`order(-1)`，单段通配不误伤 `/{id}` 与 `/me/profile`）；smoke 增 follow 冒烟、会话探针改 `/api/v1/auth/me`。AI 侧快验：三工程 test-compile + compose config + node --check + 本地 gateway 构建通过；CI 第 3 轮五 job 全绿（run 35591315920；第 2 轮部署构建竞态修复见陷阱 14）
- 已完成：**split-08：OpenFeign 回填用户域**：backend 经 `@FeignClient(name="kuros-user", url="${app.feign.kuros-user.url:}")` 消费 `/internal/v1/users` 的 batch/following/followers（url 留空走 lb 服务发现、测试注入桩地址直连）；`UserDirectoryFacade` 收敛两条**相反**降级路线——内容域列表 `findAuthors` 吞异常返空 map→占位作者"用户"不 500、资料页 `requireUser/following/followers` 失败即 503、未知 id→404；`CommunityPostService/CommunityCommentService` 整页批量回填消除跨服务 N+1（详情走单元素 batch）；`ProfileService.findPublic/findOwn` 重建为"用户字段经 Feign + postCount/likeCount/posts/comments/favorites 本地聚合"的跨服务组合（依赖单向：内容域→用户域）；`CacheConfig` 恢复 `publicProfile` 缓存名（60s TTL 兜底，事件驱动失效留待 RocketMQ 切片）。测试：`UserDirectoryStub`（JDK HttpServer 桩 + url 直连，含 healthy 降级开关）翻转作者昵称断言 + 新增"列表占位/资料页 503"降级用例；`NacosFeignIntegrationTest`（Testcontainers nacos + `NamingService` 把桩注册为 kuros-user、url 留空走 lb 真实链路）。AI 侧快验：test-compile 全绿 + code-review 子代理（静态+编译级）无阻断问题、修复桩 executor 非守护线程泄漏（陷阱 15）。**收尾还终修了 kuros-user 的锁-事务竞态 flaky**（`UserFollowService` 去类级 `@Transactional`、事务改在锁内提交，详见陷阱 7）。**CI run 35596400478 五 job 全绿**（Backend 首轮即绿；User service 首轮撞上该 flaky、重跑即绿）；工单 3 框已勾、PR body 已补 run 号（文档提交 00cc079）。flaky 终修提交 08bab65 经 **CI run 35597600427 五 job 全绿**验证（User service 确定性转绿）
- 已完成：**split-09：全链路冒烟 + 复盘收尾**（提交 46dd243）——`compose-smoke.mjs` 升级跨服务端到端链路（三服务 Nacos 注册校验 → 网关登录写共享 Redis → `/auth/me` 取权威身份 → 发帖读同一会话 → 详情作者昵称经 Feign 回填断言，基线显式拒绝降级占位值"用户"）；README 补服务拓扑/端口/网关调试说明 + HANDOFF 端口拓扑节；`docs/learning/10-service-split.md` 七段式复盘产出（含 STAR 收益量化 + 7 条追问链 + 模块化单体/一次性全拆/渐进式拆对比表）+ README 索引。AI 侧快验：compose config EXIT 0、node --check EXIT 0、code-review 子代理（静态）无阻断问题（采纳 2 条 CONSIDER）。**CI run 35599511878 五 job 全绿**：deployment job 跑 compose-smoke 两遍（创建 + 重启 mysql/backend/user/frontend 后复用），日志实证 `Nacos smoke passed: kuros-backend & kuros-user & kuros-gateway registered` + `Cross-service smoke passed: … 详情作者昵称经 Feign 回填为「无音区夜行者」`（真实昵称非占位），扛过一次全栈重启。**剩 Issue #67 验收关闭 + PR #68 合并（需用户确认）**

**已合并 PR**：#59（切片 #1-#9 汇总）、#66（Prometheus registry 修复）；`main` @ `905c7b8`

---

## 2026-09-21 方向纠偏（立即生效，适用 #11 及以后）

- **项目唯一目标**：求职作品 + 面试兵器库——"专门用于 Java 后端面试的高并发内容社区"，每个核心模块承载一个可被深挖追问的技术难点。面试评估逻辑：遇到什么问题 → 原方案为何不行 → 用什么技术 → 收益是什么。
- **切片定义模式（强制）**：① 难点叙事（具体问题 + 原方案为何不行）→ ② 方案选型（≥2 候选，选/拒理由写进 spec）→ ③ 功能载体（只做演示难点所需功能）→ ④ 验证与叙事（真实数据 + STAR 故事 + 追问链）。
- **硬性纪律**：禁止无压测依据的并发表述；禁止管理后台 CRUD 等低价值功能；面试素材复用真实踩坑，不编造。
- **技术栈锁定**：MySQL 8 + Redis + MinIO + RocketMQ；禁止迁移 PostgreSQL/pgvector/RustFS；RocketMQ 为异步主线。
- **切片优先级**：#11 互动写路径异步化 + RocketMQ → #12 Feed 流 → #13 读路径加固 → #14 搜索 + CDC → #15+ 可选增强（秒传/直传/Jmeter/Leaf/Cassandra） → AI 标签/向量推荐最后单独立项。
- **DoD 增量**：STAR 面试故事 + ≥5 条追问链回答清单 + 方案对比表 + 真实数据 + `docs/learning/` 七段式复盘。
- **执行分工调整**：耗时测试（全量 `mvn test`、Playwright e2e、compose 冒烟）**由用户执行**，AI 仅做快速编译级验证；CI 为权威验证。

---

## 项目架构概览

### 技术栈
- **后端**：Spring Boot 4.1.1 + Java 21 + Maven；Spring Cloud 2025.1.1 + SCA 2025.1.0.0
- **前端**：Next.js 15 + TypeScript
- **存储**：MySQL 8 + Flyway 迁移 + Redis 7 + MinIO
- **治理**：Nacos（注册/配置）、Sentinel（限流）、Gateway（统一入口）、SaToken 1.39（认证）
- **测试**：JUnit 5 + Testcontainers 2.0.5 + Playwright

### 后端包结构（切片 #10 Phase A 目标）
```
com.kuros.kurosbackend
├── shared/          # 共享基建：config / exception / health / api / lock（split-01 已归位）
├── user/            # 用户域残留壳（split-08 后）：资料端点经 Feign 回填恢复 200 + `client/`（UserDirectoryClient/Facade/UserBriefDto）+ StpInterfaceImpl（Redis-only）；主体已拆出 kuros-user
├── post/            # 帖子域（split-03）
├── comment/         # 评论域（split-03）
├── interaction/     # 互动域：点赞/收藏（split-04）
├── report/          # 举报域（split-04）
└── media/           # 媒体域：存储、上传（split-04）
```

> 切片 #10 完成后，`user` 域物理拆出为独立微服务 `kuros-user`（独立库 `kuros_user` 同实例，共享 Redis 会话，OpenFeign 通信）。

---

## 开发规范与约定

### 1. 拦截器顺序（重要）
```java
registry.addInterceptor(sAuthInterceptor).order(0);  // 鉴权在前
registry.addInterceptor(csrfInterceptor).order(1);   // CSRF 在后
```
**原因**：游客写操作应先返回 401（鉴权失败），而非 403（CSRF 失败）。

### 2. 公开路由白名单（方法级粒度）
```java
SaRouter.match(SaHttpMethod.GET).match("/api/v1/posts/**").stop();
```
**原因**：仅按路径豁免会导致 POST 也公开。

### 3. RBAC 查询（nativeQuery）
关联表（未映射实体）用 `nativeQuery = true`，JPQL 只能 join 已映射实体。

### 4. 事务边界（Redis 写入）
非事务资源（Redis）的写入用 `TransactionSynchronization.afterCommit()` 延迟到事务提交后，防止 DB 回滚产生孤儿数据。

### 5. CSRF 排除路径
`/api/v1/auth/**` 必须排除 CSRF 拦截器（匿名请求无 CSRF Token）。

### 6. Flyway 迁移
不依赖自增 ID 顺序，用 `role_code` 子查询保证跨环境一致；只增不改，拆分迁走的表用新版本 DROP。

### 7. 包结构与模块边界（切片 #10 后）
按业务模块分包（user/post/comment/...），依赖方向单向：内容域 → 用户域；共享代码用**有纪律复制**，不建 common 模块（rule of three）。

### 8. 端口拓扑与本地调试入口（切片 #10 后）
gateway 8080（正门，前端/冒烟唯一入口）/ backend 8090 / user 8091（均为调试直连，容器内 8080）/ nacos 8848·9848·8081 / frontend 3000。**本地调试统一走网关 8080**：`/api/v1/auth/**` 与 `/api/v1/users/*/follow` → `lb://kuros-user`，其余 → `lb://kuros-backend`。**直连 8090 时用户域端点 404 属预期**（已迁出 backend、只在 kuros-user）；跨服务数据（帖子作者昵称等）由 backend 经 OpenFeign 回访 kuros-user 回填。

---

## 测试策略

### 后端（JUnit 5 + Testcontainers）
- 主测试类：`KurosBackendApplicationTests.java`（H2 内存库 + Redis 容器）
- 集成测试：MySQL/Nacos 固定端口容器（28848 / 29848）
- 跨服务测试（#10 起）：JDK HttpServer 桩 + Feign `url` 属性直连（双接缝）

### 前端（Playwright）
68 个 e2e（登录、发帖、评论、上传、UI 回归），对着 compose 容器跑。

### 全栈验证
- `scripts/compose-smoke.mjs`：三服务 Nacos 注册校验 → 网关登录（kuros-user 写共享 Redis）→ `/auth/me` 取权威身份 → 关注 → 发帖（backend 读同一会话）→ **详情作者昵称经 Feign 回填断言（跨服务端到端）** → 评论 → 传图 → 持久化
- `scripts/session-persistence-check.mjs`：重启 user 后用旧 Cookie 验证 Redis 会话（探针 `/api/v1/auth/me`，探活直连 8091）

### 执行分工（2026-09-21 起）
上述耗时测试**由用户执行**；AI 只做编译等快速验证；GitHub Actions CI 为权威证据。

---

## 关键文件索引

> 切片 #10 Phase A 进行中，部分文件路径在归位途中；以仓库实际结构为准。

- 认证/配置：`shared/config/`（SaTokenConfigure、CsrfInterceptor、SentinelRateLimitInterceptor 等）
- 异常体系：`shared/exception/`
- 通用响应：`shared/api/`（ApiResponse、PageResult 等）
- 分布式锁：`shared/lock/DistributedLock.java`
- 存储策略：`storage/`（StorageStrategy、LocalStorageStrategy、MinIOStorageStrategy）
- 迁移脚本：`kuros-backend/src/main/resources/db/migration/`（V1~V9 已应用；V10 起用户域表整体离场）
- 用户服务：`kuros-user/`（split-06 起承载认证链路：`web/AuthController`、`service/AuthService`、`auth/`（SaToken 配置子集/CSRF/验证码三件套/StpInterfaceImpl）、`repository/`（RBAC nativeQuery）；split-07 起承载关注链路与内部 API：`service/UserFollowService`、`web/UserFollowController`、`web/InternalUserController`（`/internal/v1/users`）；Flyway 在 `kuros-user/src/main/resources/db/migration/`；建库/授权脚本在 `docker/mysql-init/`）
- 规格与决策：`docs/specs/`、`docs/adr/`、`docs/slice10-service-split-spec.md`
- 工单：`docs/tickets/split-01~09*.md`
- 学习复盘：`docs/learning/README.md`（索引）
- 上下文：`CONTEXT.md`

---

## 常见陷阱（已踩坑，面试素材库）

1. **Spring Boot 4 + Jackson 2**：`sa-token-redis-jackson` 需要 Jackson 2，显式补齐 `jackson-databind` + `jackson-datatype-jsr310`
2. **Testcontainers 2.x + Docker Engine 29**：1.x 用 API 1.32 被拒，升级 2.0.5
3. **JPQL join 未映射实体**：关联表改用 `nativeQuery`
4. **CSRF 拦截器顺序**：顺序错误导致游客写操作 403（应 401）
5. **公开路由白名单**：必须方法级 + 路径双重匹配
6. **Docker 构建 Maven 静默下载**：BuildKit 折叠日志像卡死，用 `--progress=plain` + cache mount
7. **锁-事务竞态**：锁必须在事务外层，否则锁释放时事务未提交（切片 #5 首修）。**复发与终修（split-08）**：类级 `@Transactional` 会让代理在方法体前开启事务、返回后才提交，而锁在 `finally` 释放——时序退化成 `unlock` 先于 `commit`，留下极窄窗口：启动稍晚的线程在赢家已解锁、事务未提交时拿到锁，`existsById` 读不到未提交的行→重复 insert→撞复合主键→500（`UserFollowIntegrationTest.并发重复关注` 随机飘红的根因，CI run 35596400478 首轮命中、重跑即绿）。终修：去掉 `UserFollowService` 类级 `@Transactional`，让 `TransactionTemplate` 在锁内自开自提交（`lock → tx → commit → unlock`），窗口彻底关闭。教训：“锁包裹事务”不能靠类级注解（它把 commit 推到方法返回之后），必须让事务边界显式落在锁内
8. **Nacos 集成八坑**：版本对齐、gRPC 端口偏移、IPv6、刷新时序、init SQL 建错库等（详见 `docs/learning/08-*.md`）
9. **Gateway DSL 绑定失败**：详见 `docs/learning/09-*.md`
10. **Actuator 不传递 Prometheus registry**：`/actuator/prometheus` 404，需显式加 `micrometer-registry-prometheus`（PR #66）
11. **全量测试跨类污染双根因**：① H2 库名固定 + Spring context 缓存 → @DirtiesContext 失效、数据串类（单类绿全量红）；② Sca Nacos 地址解析 JVM 级静态缓存 → 首解析地址粘住 JVM、静默回退默认值（阈值 100 vs 555）。修复：唯一 H2 库名工厂（TestDatabases）+ surefire `reuseForks=false` 每类独立 JVM（cbae01c，详见 pom 与测试类注释）
12. **mysql-init 授权脚本两坑（split-05）**：① GRANT 写死账号名——MySQL 8 起 GRANT 不再隐式建号，换 `MYSQL_USER` 后报 1410，entrypoint 带 `set -e` 使初始化整体失败（改用 `.sh` 展开环境变量；`mysql` CLI 在 source/子进程两种执行模式下都成立）；② Windows（`core.autocrlf=true`）检出 `.sh` 变 CRLF 会破坏 shebang/heredoc（目录级 `.gitattributes` 锁 `eol=lf`）
13. **跨库用户身份窗口期（split-06~08，已收口）**：认证迁入 kuros-user 后，非种子手机号首次登录只在 `kuros_user` 建号。split-06 期为"内容域按登录 ID 查 backend 本库 `users` → 404/403"；split-07 起 backend 已删用户域（V10），资料/个人中心整端 503、帖子列表作者占位"未知漂泊者"（保留 `authorId`）；**split-08 已经 Feign 从 kuros-user 回填昵称/头像/关注，窗口期收口**——列表/详情/评论作者命中即真实昵称、未命中（用户域不可用或未知 id）降级占位"用户"且不 500，资料页/个人中心恢复 200 组合视图、仅 kuros-user 不可用时 503
14. **三 Java 服务并行构建的 BuildKit 共享 cache mount 竞态（split-07 CI 第二轮）**：compose 并行构建 backend/user/gateway 共享 `/root/.m2` cache mount（默认 `sharing=shared`），冷缓存下三方同时下载解包 maven-wrapper；而 mvnw 3.3.4（only-script）以"目录存在"判断已安装（不校验 `bin/mvn` 完整性）——并发中一方看到另一方刚建的目录即跳过下载，直接 exec 未解包出的 `bin/mvn` → `exit 127`。修复：三个 Dockerfile 的挂载加 `sharing=locked`（并发构建互斥，同时消除 `~/.m2/repository` 并发写入的同类竞态）；此前轮次全绿属时序侥幸（flaky），根因是共享写入无互斥——本地热缓存永远复现不了
15. **JDK HttpServer 测试桩的 executor 线程泄漏挂住 Surefire fork JVM（split-08，code-review 静态发现）**：`HttpServer.stop(delay)` 按 JDK 契约**不关闭**调用方 `setExecutor` 传入的 executor；若用 `Executors.newFixedThreadPool`（非守护线程）且未显式关闭，残留非守护线程会阻止 fork 出的测试 JVM 自然退出——症状是构建末尾挂起或 `The forked VM terminated without properly saying goodbye`，直接威胁"全量测试绿"。修复双保险：① executor 用守护线程工厂（`thread.setDaemon(true)`），即便某测试类漏关也不挂 JVM；② `close()` 里 `server.stop(0)` 后显式 `executor.shutdownNow()`，且每个持桩测试类补 `@AfterAll` 关闭。教训：自带 executor 的 JDK 网络/服务类，stop 与 executor 生命周期是两件事，必须分别释放

---

## 下一步行动

1. **split-09 收尾**：CI run 35599511878 五 job 全绿——deployment job 已跑 compose 全链冒烟两遍（8 服务 healthy + 三服务注册 + 跨服务端到端 Feign 回填「无音区夜行者」+ 扛过重启），冒烟无需用户再手动跑 → **剩 Issue #67 逐项验收评论并关闭 + PR #68 合并（需用户确认，重大不可逆动作）**
2. **#10 收尾后**：不直接开工功能，先对 **#11（互动写路径异步化 + RocketMQ）执行 `grill-with-docs`**，按难点→方案→功能→叙事新模式出 spec
3. 本文件随进展更新

---

## 联系方式

- `CONTEXT.md`：项目上下文与决策记录
- `docs/adr/`：架构决策记录
- `docs/tickets/`：当前切片工单
- GitHub Issues：https://github.com/qingshuixiaohang/kuros/issues
