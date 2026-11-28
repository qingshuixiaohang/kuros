# 修复最终集成浏览器基线

## 问题

最终集成 Playwright 全量运行出现 4 个失败，覆盖首页加载状态可访问性、声骸详情的业务
404 与非业务错误区分，以及同人页面请求隔离断言过宽。该基线必须恢复，否则发布批次无法
取得可信的浏览器回归证据。

## 交付

- 首页 Feed 加载文案作为 live status 播报。
- 仅在声骸详情返回 `404 + ECHO_NOT_FOUND` 时显示不存在态；普通 404、500 和网络异常显示
  加载失败态。
- 同人页面隔离用例只统计精确的 `GET /api/v1/posts?category=同人` 请求。
- 本次涉及的 Playwright API mock 校验 HTTP method 与精确 pathname。
- 记录浏览器基线和临时 worktree 构建边界。

## 验收

- 受影响 Playwright 规格全部通过。
- 全量 Playwright 129/129 通过。
- ESLint、TypeScript、Vitest、Prettier 和 Webpack 生产构建通过。
- PR 手动 CI 全绿后合并。
