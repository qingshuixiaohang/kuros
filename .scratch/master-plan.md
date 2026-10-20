# kuros-web 整体进度与执行计划

> 更新：2026-10-14。本文是 leader（Hermes）的统一作战图，子任务派单以此为依据。
> 分工：Hermes 负责计划/派单/验收/git；pi 与 omp 是编码工具，不直接对外提交。

## 〇、当前批次（2026-10-14 更新，优先读这一节）

**批次：前端审计 H-9「mock 页接真数据」。** 剩 **tools / creations 两页**。

| 提交 | 内容 |
|---|---|
| `4b0641d` | 后端 news 域：V13 + 实体 + Repository + Service + Controller |
| `260d35f` | 前端 /news 三处消费点接真 API，`newsItems` 退役 |
| `6e74123` | 后端 echoes 声骸域：V14 + 实体 + Repository + Service + Controller |
| `0509f50` | 前端 /echoes 接真 API，`echoSets` 与 `EchoSet` 真删 |
| `c1e8b0e` | 补删 `newsItems`/`NewsItem`——news 域接入时漏掉的收尾 |

已完成域：characters（PR #57）、news、echoes。剩余：**tools（工具箱）、creations（同人创作）**。

**基线数字**（2026-10-15 实跑核对，非引用文档）：
- 前端 e2e **106** 例 / 13 spec（`playwright test --list` 实测；HANDOFF 的「68」是过时数，retro #17 的「95」是当时快照）
- vitest **39/39**（实测）
- 后端 **221 个 `@Test`** / 33 个测试类（`grep -rn '@Test'` 实测；retro #17 的「168」是当时快照，之后新增了 EchoApi、CharacterCatalog、缓存/锁/游标等类）
- tsc 0 error、eslint 0 warning、format:check 全绿
- 定向回归：NewsApiIntegrationTest 4/4、EchoApiIntegrationTest 5/5、KurosBackendApplicationTests 38/38、
  InteractionBatchStateIntegrationTest 8/8

**版本事实**（2026-10-15 从 `node_modules` 实测）：前端是 **Next.js 16.3.5 + React 19.2.8**。
接手简报与 HANDOFF 写的「Next.js 19」/「Next.js 15」都不对，以 package.json 为准。

**Flyway 跳号确认**：V8 不存在（V1-V7、V9-V14），master-plan 历史记录里的 L1 条目已核实。

**模式样板**（新域照抄这个形状）：`git show 4b0641d`（后端域）+ `git show 260d35f`（前端接入）。

**git 状态注意**：以上提交在本地 `fix/batch-a-security` 分支，**未推送**（该分支的 origin 还停在
`726fafb`，PR #77 已合入 main）。本地 `main` = `32ecd5b`，不含这批工作。推送前需确认目标分支。

## 一、当前进度

### 已完成并推送

| 提交 | 内容 | 分支 |
|---|---|---|
| `6cfa235` | 环境修复：JDK 21 钉死 + 内存安全启动脚本 + 行尾锁定 | main（已推） |
| `deabcf3` | CORS 响应头去重（下游+网关双重 ACAO 导致 Failed to fetch） | main（已推） |
| `08cbcc2` | **A1** dev 固定验证码不再是代码默认值 | fix/batch-a-security |
| `6217634` | **A2** `/internal/**` 共享密钥校验 + 端口绑回环 | fix/batch-a-security |
| `3c1d4ac` | **A4** 举报处置后驱逐两级缓存 | fix/batch-a-security |
| `a3db5a5` | A4 回归测试按真实契约断言（已删帖子抛 ResourceNotFound） | fix/batch-a-security |

### 测试验证结果（Docker 已启动，Testcontainers 自动拉 Redis）

| 测试 | 结果 |
|---|---|
| A4 `ReportDispositionCacheEvictionIntegrationTest` | 2/2 绿 |
| PR #57 `KurosBackendApplicationTests`（含角色图鉴 5 例 + 404 回归） | 36/36 绿 |
| 缓存四件套 `Cache*` | 10/10 绿 |
| Feed + 互动 `Feed*/Interaction*` | 18/18 绿 |
| A1 `DevCodeFailFast/SecureDefaults/RedisVerificationCodeService` | 17/17 绿 |
| A2 `InternalTokenRequestInterceptorTest` | 7/7 绿 |

**PR #57 已验证可合并**：rebase 后主测试类 36/36、缓存 10/10、Feed 18/18 全绿。

### 进行中（子代理）

| 任务 | 内容 |
|---|---|
| sa-0-999c9936 | **A3** 孤儿会话：`StpUtil.login()` 移入 afterCommit |
| sa-1-99ab78f4 | **B1** 缓存驱逐/回填写在事务内 → 抽 afterCommit 工具 |

### 待处理

- **Batch B~F**：B2 分布式锁续期、B3 互动计数对账、C1 读路径 N+1、C2 索引、D1 恒绿测试、D2 零断言测试、E 前端重构等

### 审计产出

| 报告 | 条数 | 路径 |
|---|---|---|
| 后端审计 | 高 6 / 中 15 / 低 10 | `.scratch/audit-backend.md` |
| 前端审计 | 高 10 / 中 37 / 低 5 | `.scratch/audit-frontend.md` |
| 改进计划 | Batch A~F | `.scratch/improvement-plan.md` |

---

## 二、Docker 最小服务集（内存约束）

机器 16GB，当前空闲约 5.9GB。**不拉全栈**，按需起容器：

| 目的 | 需要 | 内存量级 |
|---|---|---|
| 绝大多数后端测试（25 个测试类） | `redis:7-alpine` | ~60MB |
| kuros-user 的 2 个测试类（AuthLogin / UserFollow） | + `mysql:8.0` | ~400MB |
| Nacos 相关（3 个测试类） | + `nacos/nacos-server:v3.1.1` | ~500MB |
| ES / CDC / MinIO / RocketMQ 测试 | 各自镜像 | 按需，用完即停 |

实测清单（grep Testcontainers）：除 `AuthLoginIntegrationTest` 与 `UserFollowIntegrationTest`
需 MySQL 外，其余全部只要 Redis。**先跑 Redis 批次，能覆盖大部分验证。**

原则：一次只起当前批次需要的，跑完 `docker rm -f` 收场。

---

## 三、执行批次

### Batch A 安全止血（3/4 完成，A3 未做）

剩余 **A3**：`StpUtil.login()` 移入 `afterCommit`。
注意 SaToken 的 Cookie 写入依赖请求上下文，afterCommit 时 `SaHolder` 应仍可用（响应未提交），需实测。

### Batch B 缓存与并发一致性（技术含量最高，面试主战场）

| # | 问题 | 位置 |
|---|---|---|
| B1 | 缓存驱逐/回填写在事务内，违反项目自己的 afterCommit 纪律 | `PostPublishingService.java:108-109` 等 5 处 |
| B2 | 分布式锁无续期，TTL 硬 3s → 锁过期互斥失效 | `DistributedLock.java:61-66` |
| B3 | 定期计数校准已完成（Issue #139）；state/count 两 key TTL 独立问题未在本切片复核 | `InteractionCountReconciliationJob.java` |

### Batch C 读路径加固

| # | 问题 | 位置 |
|---|---|---|
| C1 | 帖子列表 DB 层 N+1：一页 50 条 = 101 条 SQL | `CommunityPostService.java:295,330-345` |
| C2 | `comments.author_id` 无索引 | `V4__comments.sql` |
| C3 | backend 的 `/actuator/prometheus` 不在白名单 → 指标采不动 | `SaTokenConfigure.java:57-67` |

### Batch D 测试补强（把恒绿测试变真）

| # | 问题 | 位置 |
|---|---|---|
| D1 | 429 限流测试**结构上恒绿**（阈值 100 只发 20 请求） | `RateLimitIntegrationTest.java:78-102` |
| D2 | 删帖过滤测试**零断言** | `FeedTimelineIntegrationTest.java:172-193` |
| D3 | `FeedFanoutListener`（#12 核心写路径）零覆盖 | `feed/event/FeedFanoutListener.java` |

### Batch E 前端重构

E1 mutation 层（全仓 0 处 `useMutation`）→ E2 首页 Feed N+1 → E3 六个 mock 页面 → E4 `/search` 与 following 流零 e2e → E5 `community-home.tsx` 264 行拆分

### Batch F 架构与卫生（长期）

M14 post↔interaction 双向依赖 / M15 复制代码行为漂移 / M1 无通用异常处理器 / L1 Flyway V8 跳号 / M11 无 tracing 与 logback

---

## 四、子任务派单规则

1. 每批内部按 TDD 垂直切片：先写失败测试 → 最小实现 → code-review → commit
2. 派给子代理时必须给出：文件路径、现状代码、期望行为、验证方式、禁止事项
3. 子代理只改自己任务范围内的文件，不 commit 不 push（leader 统一做）
4. 耗时验证按项目分工：全量 mvn test / Playwright / compose 冒烟由用户执行
5. 派单前先确认 Docker 里有当前批次需要的容器
