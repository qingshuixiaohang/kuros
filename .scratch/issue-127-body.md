## Parent
- Spec: #126 — 播报资讯列表加载状态

## Deliverable
为 `/news` API 列表 pending 状态添加播报语义和页面级回归测试。

## Acceptance Criteria
- [ ] 现有加载提示添加 `role="status"`，文案、条件和布局不变。
- [ ] 延迟 GET `/api/v1/news` 列表时先播报 loading，响应后显示资讯。
- [ ] mock 不拦截详情路径。
- [ ] RED/GREEN、定向 Playwright、lint、TypeScript、format:check、build 通过。

## Blocked by
- #110 — 本实现基于资讯筛选无障碍 PR，修改同一个页面文件。
