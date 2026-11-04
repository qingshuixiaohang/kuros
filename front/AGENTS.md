<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->

## 前端模块索引

新增请求、数据映射、公共组件前从这些入口查找候选，再读候选及其调用方；本索引不是完整导出清单。

- `src/lib/api.ts`：请求封装、API 函数和 DTO 类型。
- `src/lib/community-queries.ts`：已有 react-query 查询与 mutation；组件也可能有局部查询，按相关业务搜索。
- `src/lib/post-view.ts`：帖子视图转换、展示格式化与演示映射。
- `src/components/community/`：页面展示组件、Markdown、高亮、页面骨架等。
- `src/hooks/`：已有共享 hooks。

路由保留 Next.js 约定的导出；展示与业务逻辑沿相邻实现组织，不为层级整齐强制搬移。新增单元测试遵循相邻测试位置；单元测试用 Vitest，浏览器用例位于 `e2e/`。命令以 package.json 为准，验证要求见根工作流文档。
