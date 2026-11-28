## Problem Statement

`/echoes` 列表的加载中、加载失败和无匹配结果消息当前只是普通段落。异步状态变化后，屏幕阅读器用户不会被主动播报，可能不知道请求仍在进行、失败或已无匹配项。

## Solution

为这三条现有状态消息添加 `role="status"`，使其作为礼貌实时区域播报。保留文案、显示条件、查询和列表行为。

## User Stories

1. 作为使用屏幕阅读器的访客，我希望查询进行中时收到加载提示。
2. 作为使用屏幕阅读器的访客，我希望知道查询失败以及没有匹配结果。
3. 作为键盘访客，我希望状态播报不改变现有焦点和操作顺序。

## Implementation Decisions

- 仅调整 `/echoes` 列表的三条异步状态消息：loading、error、empty。
- 每条消息使用 `role="status"`；不改文案、查询状态条件、组件结构和 CSS。
- 列表有数据时不新增状态消息。
- 可逆的单页可访问性修正，不新增 glossary 术语或 ADR。
- 实现叠在 PR #113 分支上；#113 依赖 PR #82，需按该顺序进入 `main`。

## Testing Decisions

- 页面级 Playwright seam：通过 `/echoes` 和精确 GET `/api/v1/echoes` 请求观察页面公开状态。
- 延迟列表响应并确认加载消息为 status；返回空列表后确认空结果消息为 status。
- 返回列表请求错误后确认失败消息为 status。
- 不检查组件内部 state，不模拟 React Query；只断言可见页面语义。

## Out of Scope

- 详情页、其他页面的 loading/error/empty 消息、Feed 结果区域 live region。
- 文案、布局、焦点管理、重试按钮、查询行为或错误等级的改动。

## Implementation

- Ticket: #115 (blocked by #113)
- PR: #116 (open; not merged)
