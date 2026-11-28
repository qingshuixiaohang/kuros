## Parent
- Spec: #129 — 播报攻略列表加载状态

## Acceptance Criteria
- [ ] 现有“正在同步内容…”文本增加 `role=status`，其他行为不变。
- [ ] 延迟 GET `/api/v1/posts` pageSize=10 时先验证 status，再放行并验证响应攻略渲染。
- [ ] 验证 pageSize=40 右栏请求通过。
- [ ] RED/GREEN、Playwright、lint、TypeScript、format:check、build 通过。

## Blocked by
- #119 — 修改同一攻略页文件；#119 又依赖 #102。
