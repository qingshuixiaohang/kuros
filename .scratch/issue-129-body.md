## Problem Statement
`/guides` 初次加载真实列表时在结果计数位置显示“正在同步内容…”，但当前没有状态消息语义。

## Scope and Acceptance
- 只为现有列表查询 loading 文案增加 `role="status"`，文案、条件、布局和请求行为不变。
- Playwright 延迟 GET `/api/v1/posts` pageSize=10，验证 loading 状态后放行有效响应并验证攻略标题出现。
- pageSize=40 的右栏请求继续放行。
- 实现基于 PR #119（并包含其依赖 #102），不改变分类筛选。
