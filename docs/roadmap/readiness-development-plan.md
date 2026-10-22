# MVP 收口后的续开发计划

更新时间：2026-10-23（北京时间）。当前状态权威入口为
[current-status.md](current-status.md)，模型交接为 [ds41-handoff.md](ds41-handoff.md)。

## 已完成范围

#159 的八项发布加固子工单 #160–#167 均已完成、合并和关闭，PR #168 合并提交为
`9b2bc8bdebf78f3a7f4b506ab57cf67e47df0b4b`。最终 PR HEAD 上的
[CI 39809329604](https://github.com/qingshuixiaohang/kuros/actions/runs/39809329604)
五作业全部成功。实现、验证与限制见状态文档，开发复盘见 `docs/learning/32-mvp-readiness-hardening.md`。

本批次完成度为 100%；这个百分比只覆盖 #159 规格，不代表公网部署或任意后续产品需求。
截至本次核对，开放工单为 0，没有待继续实现的已排期 MVP 任务。

## 第一步：恢复工作区

1. 复用 `G:\kuros-web\.scratch\worktrees\product-readiness`，先检查分支、HEAD、
   `origin/main` 和工作树。文档收尾分支为 `codex/readiness-handoff`，可能已经合并，
   以 Git 实际结果为准。`codex/product-readiness` 是已合并的历史实现分支。
2. 主目录 `G:\kuros-web` 在其他分支，保留用户修改；不要从那里直接融合历史分支。
   当前 worktree 两份未跟踪的测试日志保留，不提交。
3. 从 GitHub 核实开放 Issue/PR。存在新任务时按工单依赖推进；不存在时进入下面的
   演示验收准备，发现可复现缺陷再建工单。

## 第二步：按演示链路验收

这是下一批建议，尚未执行；按可用内存一次选择一条链路，不启动全栈。

| 顺序 | 任务 | 验收产物 |
|---|---|---|
| 1 | 确定本次演示链路及其依赖；核对当前进程、端口和内存 | 必需服务清单、实际启动命令和资源记录 |
| 2 | 按链路验证登录、发帖/编辑、媒体、关注流、评论、举报；搜索与 CDC 独立安排 | 对应页面/API 的实际操作结果；故障有复现步骤 |
| 3 | 对真实缺陷走规格、工单、TDD、双轴 Review、PR 与针对性回归 | 可追溯的缺陷工单与修复证据 |
| 4 | 将已有 learning 复盘整理成可演示的面试路径 | 每项技术的难点、选型、真实证据与追问入口 |

已有 161 条 Playwright 是页面级接口 seam 验证；五作业 CI 包含真实 Compose 冒烟、
搜索 CDC、持久化与重启复验。人工演示结果须单独记录，不能把前述结果写成已在本机
完成的人工走查；无新改动和疑点时无需重复全量测试。

## 第三步：需要公网部署时

1. 确定部署目标、域名和 HTTPS ingress；凭据由受控环境提供。
2. 按供应商官方协议实现真实 `SmsSender` adapter，创建独立规格/工单并覆盖成功、
   失败及生产配置门禁。现有缺失 adapter 时拒绝启动的规则继续生效。
3. 核对生产密钥、内部端口、媒体域名白名单与 MinIO 访问配置；保留原 MinIO 源码
   构建方案。执行迁移前核对备份和恢复步骤。
4. 目标环境实际验证登录、媒体可见性、搜索、持久化与重启，记录版本和部署证据后
   再标记“已上线”。

这部分依赖部署环境与供应商输入，尚未执行，也不属于已关闭 #159 的欠项。

## 开发与资源纪律

新功能继续严格遵循 `docs/agents/issue-tracker.md` 中 Matt 主流程：
`grill-with-docs → to-spec → to-tickets → implement + TDD → code-review → PR/Issue → retro`。
新阶段先固化验收范围，再开展实现；第二阶段内容、推荐算法、管理后台扩展另行立项。

16GB 主机的启动与验证预算见 `docs/agents/local-memory-budget.md`：Maven 串行，
Playwright 单 worker，浏览器与 JVM 分批，Docker 只开当前任务必需服务。
纯文档改动按仓库规范通读 diff，不重复触发构建和全量 CI。
