# Kuros Web 当前状态

> 快照日期：2026-10-21
>
> 权威基线：`origin/main` @ `0dcfa14`
>
> 本页是当前进度口径。`HANDOFF.md`、`.scratch/master-plan.md`、`.scratch/audit-*.md`
> 和历史学习复盘保留旧快照与决策过程，其中的完成状态和测试数字不得直接当作当前事实。

## 已合并

- 微服务拆分、互动异步化、Feed 流、读路径加固、全文搜索与 CDC 已完成并合入主线。
- 请求关联 ID、互动计数定期对账、帖子互动读边界、MinIO 源码构建与 Compose
  开发 profile 已合入主线。
- 前端 `characters`、`news`、`echoes`、`creations` 已接真实 API。
- 配队模拟器在目标路由读取真实角色目录，并覆盖加载、失败重试、空状态和页面边界；
  工具箱目录本身是固定产品入口配置，不再承载模拟角色数据。
- 社区导航、频道抽屉、Feed 与右栏的结构化拆分已合入主线。

## 待发布

以下实现均从当前 `origin/main` 派生，工作树干净，本地验证通过；尚未推送、创建 PR
或运行该分支的手动 CI。

| 分支 | HEAD | 对应 Issue | 已有本地证据 |
|---|---|---|---|
| `codex/api-error-contract` | `ce9ad6d` | Spec #145 / Implement #146 | backend handler 契约 5/5、应用级 41/41；user handler 契约 4/4、登录集成 7/7；两侧 `test-compile` 通过；最小 Compose 栈实测 15 MB 上传返回 413 `UPLOAD_TOO_LARGE` |
| `codex/report-moderation-boundary` | `48f6a64` | Implement #147 / Spec #148 | 领域边界 5/5、举报缓存集成 5/5、后端应用级 38/38；`test-compile` 通过 |
| `codex/media-upload-consistency` | `23e2985` | Spec #149 / Implement #150 | 上传与清理合计 8/8、后端应用级 38/38；`test-compile` 通过 |
| `codex/browser-baseline-repair` | `43b043f` | 集成验证发现修复 | review 回归取得 RED 后，受影响 Playwright 15/15、全量 Playwright 129/129；ESLint、TypeScript、Vitest 39/39、Prettier、Webpack 生产构建与 `learning/30` retro 通过 |
| `codex/gateway-nacos-container-ports` | `2f15cfc` | 集成验证发现修复 | 真实 Nacos 定向 2/2；Surefire 按测试类隔离后连续两轮全量 11/11，最终集成树 11/11；Nacos 容器堆上限 512 MiB、测试 JVM 堆上限 768 MiB；`learning/31` retro 通过 |

## 发布前集成验证

2026-10-21 在包含最新 `origin/main` 的最终集成树上使用短 `TEMP`
`G:\kuros-web\.scratch\tmp` 与 Docker Desktop 实际执行：

| 检查 | 结果 |
|---|---|
| `kuros-backend\mvnw.cmd test` | 216/216 通过，0 failure、0 error，21 skipped |
| `kuros-user\mvnw.cmd test` | 51/51 通过，0 failure、0 error |
| `kuros-gateway\mvnw.cmd test` | 11/11 通过，0 failure、0 error |
| 最小 Compose 栈 15 MB 上传 | HTTP 413，`UPLOAD_TOO_LARGE` |
| `git diff --check` | 通过 |

`216/216`、`51/51` 与 `11/11` 是 Maven 全量测试汇总，不是定向测试或测试发现数量。

## 当前开放 Issue

GitHub 当前只有上述三组 Issue，且没有开放 PR：

- #145/#146：统一 backend 与 user 的 API 错误信封。
- #147/#148：举报处置回到帖子域/评论域写入口。
- #149/#150：媒体上传先登记元数据，再写对象存储。

## 前端验证基线

2026-10-21 在基于 `0dcfa14` 的最终发布集成树中实际执行：

| 检查 | 结果 |
|---|---|
| `npm run lint` | 通过 |
| `npx tsc --noEmit` | 通过，0 error |
| `npm run test` | 4 个文件，39/39 通过 |
| `npm run format:check` | 通过 |
| `npm run test:e2e -- --workers=1` | 129/129 通过 |
| `npx next build --webpack` | 生产构建通过 |

全量 Playwright 在最终集成树实际执行，不等于测试发现计数。临时 worktree 的
`node_modules` 是外部 junction，Turbopack 会将其判为超出项目文件系统根，因此生产构建
使用 Webpack 路径验证；分支 CI 仍按项目流程手动触发。

## 收口顺序

1. 推送本状态页及五个待发布实现分支，创建对应 PR，并逐个手动触发 CI。
2. 对每个 PR 复核差异、CI 与双轴 code review，通过后合并并回写对应 Issue。
3. 更新本地 `origin/main`，从新的主线重新审计开放 Issue 和源码，再决定是否还有
   可执行的路线图项。

GitHub 写入、CI 触发、PR 合并和 Issue 关闭属于外部发布动作，执行前需要针对具体
分支和 Issue 的明确授权。
