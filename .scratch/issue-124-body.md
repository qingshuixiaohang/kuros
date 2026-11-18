## Parent
- Spec: #123 — 播报首页内容流加载状态

## Deliverable
首页 Feed 加载期间的状态消息语义和页面级 Playwright 覆盖。

## Acceptance Criteria
- [ ] Feed 的现有加载文案在加载期间带有 `role="status"`。
- [ ] 延迟的精确 GET `/api/v1/posts?pageSize=20` seam 可见加载状态，响应后显示帖子。
- [ ] pageSize=40 的右栏请求未被测试替身拦截。
- [ ] RED/GREEN、针对性 Playwright、lint、TypeScript、format:check、build 通过。

## Blocked by
- #82 — 本切片基于当前首页 Feed API 结构。
