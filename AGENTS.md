<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## Project development workflow

For every new feature, follow the repository workflow in `docs/agents/issue-tracker.md` strictly: `grill-with-docs` → `to-spec` → `to-tickets` → `implement` with TDD → `code-review` → commit/PR/Issue update. Context compaction must preserve the current phase and resume from the written artifacts; it is not a reason to skip a phase.

## Unattended execution (dsh-superagent)

When the work runs **without a person present** — an Issue that mentions the agent account, executed by the `dsh-superagent` worker in its own git worktree — follow `docs/agents/autonomous-agent.md` instead of the full workflow above. It states which phases are skipped (no `grill-with-docs`, `to-spec`, `to-tickets` or `retro`), the files and operations that are off limits, the checks that must run before pushing, and the required PR and report format.

Sessions with a person present are unaffected and still follow the full workflow.

## 仓库事实（写代码前先确认，别自己猜）

- **异常处理**：`kuros-backend` 与 `kuros-user` 各有 `shared/web/ApiExceptionHandler.java` 统一处理异常。Service 层不要再 try-catch 业务异常后包一层返回。
- **参数校验**：当前仓库没有使用 `@Valid` / `jakarta.validation` 注解（全仓 0 处）。新增校验前先看同模块现有写法并保持一致，不要单独引入一套新范式。
- **前端请求**：所有 HTTP 都走 `@/lib/api` 导出的函数，组件里不直接调 `fetch`。数据获取用 `@tanstack/react-query`，hook 集中在 `front/src/lib/community-queries.ts`。
- **类型来源**：后端 DTO 的 TS 类型在 `front/src/lib/api.ts`；领域视图模型与格式化在 `front/src/lib/post-view.ts`。
- **测试**：vitest；测试文件放在被测文件同级的 `__tests__/` 目录。
- **必填配置**：缺少必需的环境变量或配置时应当启动失败，不要给默认值兜底。

## 代码风格：反过度工程

抽象的判据是存量代码的重复，不是对未来的猜测。

1. 内部函数不再校验参数；校验只在边界入口（Controller / API 层）做一次。
2. 类型系统已排除的分支不写运行时检查；`any` / `unknown` 兜底必须写明理由。
3. 抽象来自真实重复：只有已存在 ≥2 个调用点且差异真实，才抽接口、策略或工厂。单一实现不抽接口；PR 描述要指出那 ≥2 个调用点。
4. 不为"将来可能需要"预留参数、配置项或抽象层。
5. 新增 `try/catch` 必须写明捕获什么异常、捕获后做了哪件不同的事；否则删除。
6. 注释写 why（非显然的约束、坑、来源），不写 what。
7. 早返回优先，嵌套不超过 3 层（lint 当前允许 4 层，存量清理后收紧）。
8. 新增工具函数前先查 `front/src/lib/`（见 `front/AGENTS.md` 的积木清单）；PR 描述写一句"为什么不用现有实现"。
