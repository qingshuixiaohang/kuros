## Problem Statement
首页内容流在等待 GET `/api/v1/posts` 时显示“正在整理漂泊者的最新内容…”，但没有将其标记为状态消息，屏幕阅读器用户不会及时获知内容加载状态。

## Scope
仅将首页 Feed 主请求的现有加载消息暴露为状态消息，并用页面级测试覆盖延迟的 Feed API 响应。

## Acceptance Criteria
- loading 文案、条件和视觉样式保持不变，并通过 `role="status"` 播报。
- 页面级 Playwright 在延迟的精确 Feed 列表请求期间可定位该状态消息，随后正常显示响应内容。
- 只拦截 GET `/api/v1/posts` 且 pageSize=20 的首页 Feed 请求；右栏 pageSize=40 请求继续放行。
- 不改空态、错误态、重试或 Feed 数据行为。
