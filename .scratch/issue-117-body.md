## Problem Statement

`/guides` 的“全部、配队攻略、深塔攻略、角色培养、新手攻略”分类按钮会实际过滤后端帖子，但当前只通过 `is-active` 视觉样式表示选中项。屏幕阅读器用户无法识别这组按钮用于攻略分类，也无法获知当前分类。

## Solution

给分类按钮组添加可访问名称，并用 `aria-pressed` 表示当前分类；保留既有 API 筛选和页面行为。

## User Stories

1. 作为屏幕阅读器用户，我希望知道这些按钮属于攻略分类筛选。
2. 作为屏幕阅读器用户，我希望知道当前选中的攻略分类。
3. 作为键盘用户，我希望原生按钮继续按现有方式筛选内容。

## Implementation Decisions

- 仅调整 `/guides` 分类筛选按钮组。
- 分组通过 `role="group"` 和名称“攻略分类筛选”暴露；每个按钮以 `aria-pressed` 表示当前 category。
- 保持默认分类、筛选请求、分页重置、文案、CSS class、布局和结果行为不变。
- 不将筛选按钮改为 ARIA tab 模式，不调整标签、排序或分页控件。
- 页面与测试文件正被 PR #102 修改；实现叠在该 PR 分支上，依赖 #102。

## Testing Decisions

- 页面级 Playwright seam：访问 `/guides` 并 mock 帖子列表 API。
- 验证组名、所有按钮的布尔 `aria-pressed`、默认“全部”唯一选中。
- 选择“角色培养”后验证唯一选中项移动，且列表显示该分类响应。
- 使用精确 GET + pathname 匹配，只 mock 列表路由。

## Out of Scope

- `/guides` 搜索、标签、排序、分页、加载/错误/空状态及详情页。
- 其他页面或首页导航。

## Implementation

- Ticket: #118 (blocked by #102)
