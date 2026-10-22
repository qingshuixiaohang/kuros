# Kuros Web 当前状态

> 快照日期：2026-10-22
>
> 权威基线：`origin/main` @ `d16cbc6`
>
> 本页是当前进度口径。`HANDOFF.md`、`.scratch/master-plan.md`、`.scratch/audit-*.md`
> 和历史学习复盘保留旧快照与决策过程，其中的完成状态和测试数字不得直接当作当前事实。

## 发布结论

本轮路线图中已登记的功能、技术债和发布阻塞项均已完成：

- 微服务拆分、互动异步化、Feed 流、读路径加固、全文搜索与 CDC 已合入主线。
- 请求关联 ID、互动计数定期对账、帖子互动读边界、MinIO 源码构建与 Compose
  开发 profile 已合入主线。
- 前端 `characters`、`news`、`echoes`、`creations` 和 tools 配队入口已接真实 API。
- API 错误信封、举报处置边界、媒体上传一致性与浏览器基线修复已合入主线。
- Gateway 的真实 Nacos 服务发现集成测试已恢复为可重复执行的发布门禁。

完成本页对应的 #156 合并后，本轮发布不再有未关闭的实现 PR 或已知阻塞 Issue。

## 本轮已合并

| PR | 合并提交 | 交付 |
|---|---|---|
| #151 | `0f32979` | 统一 backend/user API 错误信封（#145/#146） |
| #152 | `e83d325` | 举报处置回到帖子域/评论域写入口（#147/#148） |
| #153 | `0061fe0` | 媒体上传先登记元数据，再写对象存储（#149/#150） |
| #154 | `c944d12` | 恢复浏览器全量测试基线（#157） |
| #155 | `d16cbc6` | 修复 Gateway Nacos 集成测试资源边界（#158） |

## CI 证据

| 检查 | 结果 |
|---|---|
| #151 手动 CI | [39624369196](https://github.com/qingshuixiaohang/kuros/actions/runs/39624369196)，五作业通过 |
| #152 手动 CI | [39628741761](https://github.com/qingshuixiaohang/kuros/actions/runs/39628741761)，五作业通过 |
| #153 手动 CI | [39633067675](https://github.com/qingshuixiaohang/kuros/actions/runs/39633067675)，五作业通过 |
| #154 分支 CI | [39621311303](https://github.com/qingshuixiaohang/kuros/actions/runs/39621311303)，通过；合并状态由后续主线 CI 复验 |
| #155 手动 CI | [39735808203](https://github.com/qingshuixiaohang/kuros/actions/runs/39735808203)，五作业通过 |

`ci.yml` 的五个作业分别是 backend、user、gateway、frontend lint/build 和 deployment
configuration。部署作业实际启动 Compose 栈，检查健康状态、业务数据、文件持久化、
搜索 CDC 以及服务重启后的恢复，不只是一次配置语法检查。

## 最终集成基线

2026-10-21 在包含本轮全部实现的最终集成树上实际执行：

| 检查 | 结果 |
|---|---|
| backend Maven 全量 | 216/216，通过，21 skipped |
| user Maven 全量 | 51/51，通过 |
| gateway Maven 全量 | 11/11，通过 |
| `npm run lint` | 通过 |
| `npx tsc --noEmit` | 通过，0 error |
| `npm run test` | 4 个文件，39/39 通过 |
| `npm run format:check` | 通过 |
| `npm run test:e2e -- --workers=1` | 129/129 通过 |
| `npx next build --webpack` | 生产构建通过 |

Maven 数字是全量测试运行汇总；Playwright 的 `129/129` 是实际执行结果。测试发现数量、
历史交接中的旧数字及针对性测试结果均不能替代这张最终证据表。

## 当前开放项

- 本轮路线图实现 Issue：#145–#150、#157、#158，已关闭。
- 本轮发布 PR：#151–#156，已合并。
- 已知阻塞 Issue：无。

GitHub Actions 当前对 `actions/checkout@v4`、`actions/setup-java@v4` 和
`actions/setup-node@v4` 给出 Node.js 20 弃用告警。五个 CI 作业仍正常通过；这些
action 主版本升级属于后续维护项，不构成本轮发布阻塞。

## 环境边界

- Gateway 的固定 Nacos 宿主端口要求测试进程串行运行。
- Windows 上 Maven/Testcontainers 遇到宿主临时目录导致的 JDK 回环连接失败时，先把
  `TEMP`/`TMP` 指向工作区短路径后重跑，并把宿主问题与应用回归分开记录。
- 临时 worktree 通过 junction 复用 `node_modules` 时，Turbopack 会拒绝项目文件系统
  根之外的链接；生产构建使用仓库支持的 Webpack 路径完成验证。

## 历史文档口径

本页记录已完成发布后的真实基线。`HANDOFF.md`、`.scratch/master-plan.md`、
`.scratch/audit-*.md` 和 `docs/learning/` 用于追溯决策与学习过程；其中的阶段进度、
待发布状态和测试数字不能覆盖本页的当前事实。
