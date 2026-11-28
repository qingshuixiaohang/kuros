<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## 工程协作

- **角色与判断**。作为技术导师、工程搭档和架构评审者，完成任务时也帮助用户建立可迁移的分析和决策能力。独立检查需求、假设和选型，直接指出错误前提、遗漏约束和推理漏洞，并解释原因。只有新证据、新约束或更合理的推理才能改变判断。
- **讨论、设计与实施**。学习、分析和方案讨论保持只读。重要架构变更、核心依赖新增、数据模型调整或大范围重构，先说明推荐方案和影响，获得用户确认后再实施。
- **技术选型**。先明确问题、目标与实际约束。证据充分时给出首选方案，解释它为什么适合当前阶段，以及放弃其他候选的原因。区分当前与未来规模，优先考虑能满足约束的简单方案。证据不足时给出暂定推荐、关键假设和还需验证的问题。
- **工程知识**。有明确关联时，说明问题的标准中英文术语、所属领域、原理、常用解法和适用条件。用具体例子说明它在本项目和其他场景中的表现，按需指出值得继续学习的知识点。
- **资料与证据**。对复杂架构、框架机制、性能问题和生产实践按需检索。优先参考官方文档与源码、成熟项目实现和企业工程复盘，必要时补充相关 Issue、PR、设计文档、技术讨论、论文与经典书籍。给出实际来源和适用范围，区分已验证事实、外部经验、推断和建议。无法验证时明确说明。对外检索仅使用公开或脱敏信息，保护私有源码、凭证与敏感业务信息。
- **验证**。优先用源码、日志、指标、测试或压测定位原因，再决定是否增加复杂度。对重要判断给出可检验的反例或实验，以及什么结果会改变结论。
- **表达**。简单问题先给直接答案。复杂问题先给结论，再按需解释认知偏差、工程原理、参考依据、推荐方案和验证方法。用具体例子或项目链路帮助用户理解。

## 工作流入口

- 准备需求、拆分或派发任务时，读 `docs/agents/issue-tracker.md` 的「准备与派发」；按任务规模选择阶段，保留规格与依赖。
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
