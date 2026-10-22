# ds4.1 接手提示词

将以下内容交给在本机运行的新开发会话。状态快照为北京时间 2026-10-23；所有状态都先
核实再使用。

```text
接手 Kuros Web（鸣潮攻略社区，同时用于 Java 后端求职与面试展示）。

工作区使用 G:\kuros-web\.scratch\worktrees\product-readiness。
先读该工作区 AGENTS.md、docs/agents/issue-tracker.md、
docs/roadmap/current-status.md、docs/roadmap/readiness-development-plan.md、
docs/agents/local-memory-budget.md 和 docs/learning/32-mvp-readiness-hardening.md。
HANDOFF.md 只作历史背景；旧提交、用例数量、未完成清单均不自动成为当前事实。

恢复后先执行只读核对：
  git status --short --branch
  git log --oneline --decorate -8
  git branch -vv
  git worktree list
  git rev-parse HEAD
  git rev-parse origin/main
  gh pr view 168 --json state,mergeCommit,url
  gh issue list --state open
如有网络与 Git 元数据权限，git fetch origin main 后再核对；本地 origin/main 可能过期。

已核实的上一阶段成果：#159–#167 已全部关闭，PR #168 squash 合并为 9b2bc8b。
最终 CI run 39809329604 在 270b7fa 上五作业成功。证据、限制和后续计划均见上述文档。
当前处于合并后交接阶段，没有已经排好但尚未实现的 MVP 工单。
本轮文档收尾可能使 HEAD 和 main 继续前进，以命令结果为准。

先汇报当前工作树、开放工单和下一步验收范围；随后按续开发计划推进。
不要因历史计划写了“先做 tools/creations”就重新实现已交付的功能。
公网部署、真实短信 provider 与 HTTPS 仍未完成；未提供的供应商协议与凭据不能编造。

流程：grill-with-docs → to-spec → to-tickets → implement + TDD → code-review
→ commit/PR/Issue → retro。GitHub Issues 是任务权威来源，文档收尾不重复创建功能规格。
用户已授权此前批次的推送、PR、CI、合并与工单关闭；其他操作遵循新会话权限与当前授权。

技能：优先读取本机 C:\Users\Administrator\.agents\skills\ask-matt\SKILL.md
及与当前阶段相关的技能。具体行为以实际 SKILL.md 为准。
kuros-coding-team 在本次常用技能目录搜索中未找到，不能声称已加载；若新环境存在，
读取后再使用。缺少技能时先报告，并保留仓库已记录的开发流程。

资源：16GB 主机，每批先查当前内存。一个 Maven 工程串行执行，使用已有堆限制；
Playwright --workers=1。Next/浏览器与 Maven 重任务分批。
Docker 只开当前批次需要的服务，全栈冒烟优先用远端手动 CI。
MinIO 保留原实现和从源码构建方案，不擅自切换存储产品。

文件：主目录 G:\kuros-web 位于其他分支且可能有用户修改，先核查再操作。
.scratch、storage、容器数据与历史 worktree 保留；测试日志不提交。
当前工作区有两份未跟踪的 backend-targeted-after-fix*.log，属于本地失败证据。

验证：运行与改动相符的检查。纯文档通读 diff；代码按 issue-tracker 的分级表。
新功能先取得业务 RED，再最小实现 GREEN；环境启动错误不能记为业务 RED。
改 Next 前读 front/node_modules/next/dist/docs/ 中相关指南。
Windows Testcontainers 先按 issue-tracker 设置短 TEMP/TMP；若仍有 selector/socket
错误，记录真实失败，使用远端 Linux CI 复验。不要为了测试启动全部中间件。
完成当前改动并取得必要证据后继续交付，避免对纯文档反复跑全量 CI。
```

## 建议按阶段使用的技能

- 需求和规格：`ask-matt` 路由到实际安装的 `grill-with-docs`、`to-spec`、`to-tickets`。
- 代码实现：`implement`、`tdd`；复现疑难故障时用 `diagnosing-bugs`。
- 收口：`code-review`（规范与规格）、`pr`、`retro`。
- 修改 agent 交接或工作纪律：`writing-for-agents`。

技能未安装时明确说明；不把技能名称当成已执行证据。
