# 配队模拟器接入真实游戏角色图鉴

> 对应 Issue：[#78 规格](https://github.com/qingshuixiaohang/kuros/issues/78) / [#79 实现工单](https://github.com/qingshuixiaohang/kuros/issues/79)

## 目标与结果

配队模拟器原先直接读取前端 mock 角色。现在它复用公开角色目录 API，工具目录和养成计算器不发起角色请求，现有三槽和最多三人的选择逻辑保持不变。

## 实现与验证

- 用 React Query 仅在 `team-builder` 路由请求角色目录，读取 API 支持的最大页大小 50。
- 页面分别展示加载中、请求失败并可重试、成功但列表为空的状态；请求失败不会显示 mock 角色。
- Playwright 页面边界测试覆盖 API 数据和选择、加载、失败重试、空列表，以及目录/计算器无请求。
- TDD：实现前定向 Playwright 为 4 failed / 1 passed；实现后为 5 passed。
- `npm run lint`、`npx tsc --noEmit`、`npm run format:check` 和 `npm run build` 均通过。构建需能访问 Google Fonts。

## 复盘

这次切片不需要增加后端实体或接口。页面边界路由 mock 可以单独验证 React Query 的请求状态和不回退 mock 的行为，同时断言旁路工具不会触发无关请求。

角色目录目前最多取一页 50 条，满足现有 API 的分页上限。若后端目录容量或接口分页契约改变，需要重新评估配队页是否应提供分页或加载更多。
