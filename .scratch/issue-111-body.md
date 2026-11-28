## Problem Statement

`/echoes` 提供“套装、单体声骸、掉落位置”三个展示视角。它们只改变表格第二列的内容，不过滤声骸列表；当前页面仅用视觉样式表示选中项，屏幕阅读器用户无法识别这组控件的用途和当前视角。

## Solution

给展示视角按钮组添加可访问名称，并用 `aria-pressed` 表示当前视角。保留既有按钮、数据和显示行为。

## User Stories

1. 作为屏幕阅读器用户，我希望知道这些按钮用于切换声骸展示视角。
2. 作为屏幕阅读器用户，我希望知道当前选中的展示视角。
3. 作为键盘用户，我希望保留现有原生按钮的 Tab 和激活行为。

## Implementation Decisions

- 仅调整 `/echoes` 列表页的三个展示视角按钮。
- 分组用 `role=group` 与名称“声骸展示视角”暴露；按钮用 `aria-pressed` 表示 `mode`。
- `套装` 初始选中；切换视角只更改第二列表头/单元格展示，不过滤数据。
- 不修改搜索过滤、API、数据计数、布局、按钮文案或视觉样式。
- 这是可逆的单页语义增强，不需要新术语或 ADR。
- `/echoes` 源码只存在于开放 PR #82 的分支中，故本实现叠在 `fix/batch-a-security`，依赖 #82 合并后方可落地。

## Testing Decisions

- 页面边界 Playwright seam：访问 `/echoes`，通过已有列表响应展示四套声骸。
- 验证分组名称、全部按钮都有布尔 `aria-pressed`、且始终只有一个按钮选中。
- 验证初始选中“套装”；选择“掉落位置”后选中状态移动，且表格中原有声骸仍全部可见。
- 扩展 `front/e2e/echoes.spec.ts` 的既有展示视角场景，保持 API mock 与生产逻辑不变。

## Out of Scope

- `/echoes` 搜索框、本地关键字过滤、服务端角色过滤、详情页与其他页面控件。
- 把展示视角改为过滤器或 ARIA tab 模式。

## Implementation

- Ticket: #112 (blocked by #82)
- PR: #113 (open; not merged)
