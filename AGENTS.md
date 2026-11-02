<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
## 工作流入口

- 准备需求、拆分或派发任务时，读 `docs/agents/issue-tracker.md` 的「准备与派发」；按任务规模选择阶段，保留规格与依赖。
- Superagent Worker 开工时读 `docs/agents/autonomous-agent.md`；准备阶段由派发方完成，Worker 依据原始 Issue 实现。
- 推送前读 `docs/agents/issue-tracker.md` 的「本地验证分级」及「效果证据」。审查 PR 时读 `docs/agents/review-checklist.md`。
- 涉及业务术语时读 `GLOSSARY.md`；压缩或交接前落盘当前阶段、基准提交、检查结果和剩余事项。

## 实现与审查共用准则

1. 新增公共能力前，沿调用路径搜索同类实现。前端从 `front/AGENTS.md` 的模块索引开始；后端先看同模块及 shared。存在相近候选但选择新建时，解释契约或职责差异。
2. 新增判空、默认值、配置清洗或 catch 前，确认输入来源和真实契约（校验、类型、schema、调用方）。只处理真实可能的失败；必要配置缺失明确报错，合法可选配置可用有依据的默认值。
3. 外部输入用 `unknown` 表达未验证状态并在边界收窄；内部代码复用已建立的契约。TypeScript 类型不是外部数据的运行时保证。
4. 抽象用于封装真实重复、变化边界、外部依赖或复杂性。先考虑直接实现；新增接口、策略或工厂说明当前用途，调用点数量本身不构成理由。
5. catch 用于恢复、清理、转换边界错误或补充必要上下文；没有处理策略时让异常到达已有处理层。修改异常行为前先读该模块的异常处理器与调用方。
6. 按业务顺序组织主路径，以准确命名和适当提取降低跳转；注释解释约束与取舍。遵守现行 lint，审查时关注嵌套背后的复杂性，不为层数机械拆方法。
7. 改动聚焦已授权结果；保留必要的安全检查、失败语义和测试。范围外的整理另记任务；测试更新应解释原断言为何变化。

这些准则指导实现与审查，不是文件访问或命令执行的安全沙箱。
