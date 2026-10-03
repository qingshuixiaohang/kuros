# Issue 追踪约定

本项目使用 GitHub Issues 管理需求、技术任务和缺陷：

- 仓库：[qingshuixiaohang/kuros](https://github.com/qingshuixiaohang/kuros)
- 默认分支：`main`
- GitHub Issues 是唯一的任务记录入口；讨论结论应回写到对应 Issue。
- Pull Request 用于承载代码变更和 Review，不替代需求 Issue。

## 推荐生命周期

`needs-triage` → 规格澄清 → `ready-for-agent` → 实现与测试 → Code Review → 合并并关闭 Issue

每个实现任务尽量是一个可独立验收的垂直切片，例如“登录弹窗 + 登录接口 + 登录态展示”，而不是只按前端、后端拆成两个无法验收的任务。

## 功能开发硬性流程

后续新增功能必须严格遵循 `ask-matt` 主流程，不因对话变长或实现方便而跳步：

1. `grill-with-docs`：澄清需求、边界和验收标准，并把关键决定写入 `CONTEXT.md` 或 ADR。
2. `to-spec`：多会话功能先形成可实现的规格；单会话小改动也要在 Issue 中写清范围。
3. `to-tickets`：拆成可独立验收的垂直 Issue，声明依赖和阻塞边。
4. `implement` + `TDD`：按 Issue 工作，先写失败测试，再实现最小行为，最后补边界测试。
5. `code-review`：提交前从规范和规格两个维度审查差异，修复发现的问题。
6. `commit / PR / Issue`：提交、推送 PR，回写测试结果、已知限制和关联关系。

如果上下文接近上限，必须先在阶段边界执行 `compact` 或 `handoff`，保留当前规格、Issue、测试和未完成项；恢复后从最近的阶段继续，不得凭记忆跳过 `to-spec`、`to-tickets`、TDD 或 Review。已有历史代码可以保持原状，但从下一项功能开始执行本流程。

## 本地验证分级（AI 快验约定，2026-10-03 起）

CI 是权威验证。AI 在推送前的本地验证按改动类型**分级**执行，目的只有一个：用最少的本地时间换"CI 第一轮就绿"，避免推送后等 20+ 分钟才暴露低级错误。任何分级都**不要求** AI 跑全量 `mvnw test`、Playwright 全套或 compose 冒烟（既定分工不变：这些由 CI deployment job / 用户执行）。

| 改动类型 | AI 推送前的本地验证 | 原因 |
|---|---|---|
| 纯文档（docs/、README、CONTEXT、ADR、Issue 回写） | 不跑构建，通读 diff 即可 | 文档错误不影响运行，CI 全绿照常 |
| 前端代码（front/，未动依赖） | `npm run lint` + `npm run build`；若改动组件的 e2e spec 也有增改，顺带跑对应 spec | 本地 2~3 分钟能挡住绝大多数前端错误 |
| 后端代码（kuros-*，未动依赖与配置） | `mvnw test-compile` + 只跑改动域的定向测试（`-Dtest=改动的测试类`） | 定向测试把反馈从 CI 的 20+ 分钟压到几分钟；全量留给 CI |
| 后端高危面：Flyway 新迁移、shared/ 公共类、鉴权/拦截器配置 | 上行之外必须实际跑受影响的测试类（H2+Testcontainers，需 Docker Desktop 开着） | 这类错误影响所有调用方，是 CI 红灯的最大来源 |
| 依赖/构建配置（pom.xml、package.json、compose.yml、ci.yml、Dockerfile） | `mvnw test-compile` 或前端 build，加 `docker compose config --quiet`；compose.yml 大改尽量本地起一次栈 | 配置错误最贵（构建跑到一半才炸），值得多花几分钟 |
| 极小改动（单文件几行、无逻辑分支） | 可以什么都不跑，直接推送信任 CI | 本地验证的价值是省"CI 红一轮"的往返，改动越小越不值得本地跑 |

## 常用命令

```powershell
gh issue list --state open
gh issue view <number> --comments
gh issue create --title "..." --body "..."
gh issue comment <number> --body "..."
gh issue close <number> --comment "..."
```

提交前先同步 Issue 状态；实现完成后在 Issue 中补充测试结果、已知限制和关联 PR。
