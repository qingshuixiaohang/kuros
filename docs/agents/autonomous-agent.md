# 无人值守 Agent 执行约定

适用于**没有人在场**执行任务的 agent —— 当前为 `dsh-superagent` 的 worker 会话。
有人在场的会话仍按 `issue-tracker.md` 的硬性流程执行；本文件只补充"无人值守车道"的差异，**不替代**它。

## 何时适用

- 任务由 GitHub Issue 派发，Issue 正文由维护者 @ 了智能体账号；
- 执行环境是**每工单独立的 git worktree**，执行过程中无法与维护者对话，只能报告 `blocked` 并提一个具体问题。

## 与主流程的差异（唯一允许的偏离）

| 主流程步骤 | 无人值守车道怎么办 |
|---|---|
| `grill-with-docs` 澄清需求 | **跳过**。Issue 正文就是需求来源。缺关键信息时不要猜，报告 `blocked`，只提一个问题 |
| `to-spec` / `to-tickets` | **跳过**。一单一个可独立验收的垂直切片，不做跨 Issue 拆解 |
| `implement` + TDD | **保留**。改动有测试覆盖时，先写失败测试再实现 |
| `code-review` | **保留**。提交前自查 diff，只保留与 Issue 范围相关的改动 |
| `commit / PR / Issue` | **保留**。见下方"提交与 PR"，并在 Issue 回写一条评论 |
| `retro` | **跳过**。改进项留给人维护者 |

## 范围纪律

- 只改 Issue 要求所需的文件。**不顺手重构、不重排格式、不动无关文件**。
- 默认**不新增依赖、不升级版本**；确需变动时报告 `blocked` 并说明理由与替代方案。
- **禁止改动**：`docker/`、`compose.yml`、`storage/`、`.github/workflows/`、各服务的 `src/main/resources/db/migration/`、任何密钥/凭据文件、`.scratch/`。
- **禁止操作**：`git push --force`、rebase 已推送的分支、关闭或修改他人 Issue 的标签、开启 auto-merge、删除分支或 worktree。
- 根 `AGENTS.md` / `front/AGENTS.md` 里的 `BEGIN:nextjs-agent-rules` 块由 `next dev` 自动写回；跟随提交即可，不要手工删除。

## 提交与 PR

- 分支：`agent/<工单短名>`（superagent 的 prepare 已按此创建，不要另开分支）
- 提交信息：`<type>(<scope>): <中文摘要>`，与仓库现有风格一致（`fix` / `feat` / `docs` / `refactor` / `test` / `chore`）
- PR 标题：`<type>(<scope>): <摘要>`
- PR 正文必须包含四段，缺一不可：
  1. **改了什么** —— 文件级清单
  2. **验了什么** —— 逐条命令 + 真实结果
  3. **复用说明** —— 为什么不用 `front/AGENTS.md` 积木清单里的现有实现（查过没有则写「无」）
  4. **已知限制 / 风险** —— 没验的、可能被影响的
- PR 正文首行写 `Fixes #<issue 编号>`。
- 不开启 auto-merge，由维护者合并。

## 必须跑的验证

推送前按 `issue-tracker.md` 的「本地验证分级」表执行（**命令与分级以那张表为唯一权威**，本文件不复述）。无人值守场景的增量是：

- 命令在**本工单的 worktree 里**执行，不是主工作区。
- `docker` / `docker-compose` 在本环境被禁用（已配置 `blockedCommands`）；需要起容器才能验证的改动，报告 `blocked`，不要尝试绕过。
- 实际执行过的命令与输出摘要必须写进 PR 正文和 Issue 回写评论；没跑就不要声称跑过。

## 需要类型信息的 lint 规则（尚未启用）

`@typescript-eslint/no-unnecessary-condition` 能直接拦住"类型已排除还判空"这类过度防御，但它要求 type-aware linting，且会在存量代码上报出一批问题。启用前先跑一次基线并单独提 PR，**不要在本工单里顺手打开**。

## 维护者侧

这一单的审查按 `review-checklist.md` 逐条执行；其中"复用说明"与"夹带检查"依赖本文件上面的范围纪律。

## 报告纪律

- 完成且有 PR → 报告 `ready`，附 PR 编号与链接。
- 无法继续 → 报告 `blocked`，一句话说清缺什么，**只提一个具体问题**。
- 没有可做的事（问题不成立、已修复、超出范围）→ 报告 `dropped`，写明原因。
