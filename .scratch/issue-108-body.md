## Problem Statement

资讯列表中的“全部、版本前瞻、官方公告、活动资讯”按钮没有通过语义暴露其筛选分组和当前选项。屏幕阅读器用户只能从视觉样式判断正在查看的分类。

## Solution

为资讯分类按钮组提供可访问名称，并通过 `aria-pressed` 暴露当前选中项；保持现有分类请求和页面外观不变。

## User Stories

1. 作为使用屏幕阅读器的访客，我希望知道这些按钮属于资讯分类筛选，以便理解它们会改变列表内容。
2. 作为使用屏幕阅读器的访客，我希望知道当前资讯分类，以便确认正在查看的内容范围。
3. 作为键盘访客，我希望按钮继续使用现有点击和 Tab 操作，以便筛选行为保持一致。

## Implementation Decisions

- 仅调整 `/news` 列表的分类筛选按钮组。
- 分组以 `role=group` 和明确名称“资讯分类筛选”暴露；每个按钮用 `aria-pressed` 表示当前分类。
- 保持按钮元素、分类值、query key、API 请求、文案、CSS class 和布局不变。
- 不改资讯右栏 tab 或其他页面的筛选控件。
- 可逆的单页语义增强，不新增 glossary 术语或 ADR。

## Testing Decisions

- 使用 `/news` 页面与列表 API 响应作为 Playwright seam。
- 验证分类组 accessible name、默认“全部”选中与其余项未选中；点击“官方公告”后只该项选中，并确认对应列表内容仍展示。
- Mock 精确匹配 GET `/api/v1/news` pathname；query 参数通过 URL 解析，详情请求继续放行。

## Out of Scope

- 资讯详情、首页右栏资讯 tab、其他页面筛选。
- 筛选请求语义、路由参数、样式、键盘模型或视觉改版。

## Implementation

- Ticket: #109
- PR: #110 (open; not merged)
