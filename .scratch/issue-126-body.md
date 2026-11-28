## Problem Statement
`/news` 等待真实列表 API 时显示“正在整理资讯…”，但状态没有播报语义，辅助技术用户无法及时获知加载进度。

## Scope
只为 `/news` 列表现有 pending 文案增加 `role="status"`，并增加页面级延迟 API 测试。

## Acceptance Criteria
- 加载文案、条件、布局和查询行为不变，仅增加状态语义。
- Playwright 通过延迟列表请求确认加载时可访问状态可见，响应后资讯项出现。
- Mock 仅限 GET `/api/v1/news` 列表路径，不拦截其他资讯路由。
- 基于 PR #110（该分支修改同一页面文件），依赖 PR #110。
