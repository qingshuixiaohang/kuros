# 同人创作页复用已发布帖子目录

> 对应 Issue：[#80 规格](https://github.com/qingshuixiaohang/kuros/issues/80) / [#81 实现工单](https://github.com/qingshuixiaohang/kuros/issues/81)

## 目标与结果

同人页此前把角色 mock 当成玩家作品展示。实现复用公开帖子列表 API，按精确分类“同人”查询最新 20 条已发布帖子，并通过现有帖子详情路由打开作品。`同人帖` 是普通帖子的一种分类，不是新的帖子类型或后端域；该含义已记入 glossary。

## 实现与验证

- 卡片映射复用 `toGuide` 处理帖子媒体，展示标题、摘要、作者和发布时间；有 API 配图时显示首图，无图时保留文字卡片。
- 页面展示加载、错误、成功空列表三种状态，失败不渲染旧 mock 内容。
- 页面专用 `retry: false` 让不可用状态即时显示；共享查询 hook 的其他调用方保留 React Query 默认行为。
- Playwright 通过 `/api/v1/posts` 页面边界路由验证分类、页码、页大小、排序、卡片、详情跳转、加载、错误、空状态和页面请求范围。
- TDD：实现前定向 Playwright 为 4 failed / 1 passed；实现后为 5 passed。
- `npm run lint`、`npx tsc --noEmit`、`npm run format:check`、`npm run build` 均通过。

## 复盘

列表筛选由现有 API 的分类参数和服务端精确分类契约负责。测试夹具模拟该服务端过滤行为；如果页面漏传分类，夹具会返回包含攻略分类的混合条目，列表断言便会失败。重复在前端过滤会复制服务端规则，因此这里验证请求契约和可见结果即可。

帖子详情的通用视图类型名仍为 `Guide`。本切片复用现有 `toGuide` 媒体映射，没有顺手改写公共领域类型；将来若继续扩展普通帖消费者，可单独评估更贴合帖子语义的共享视图类型。
