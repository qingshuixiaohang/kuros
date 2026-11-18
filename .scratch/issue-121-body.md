## Parent
- Spec: #120 — 全站键盘跳转到主要内容

## Deliverable
全站可键盘访问的 skip link：首页和频道页均能聚焦到主要内容。

## Acceptance Criteria
- [ ] 全站页面顶部提供指向 `#main-content` 的“跳到主要内容”链接。
- [ ] 链接默认不改变布局，聚焦时可见。
- [ ] 首页与共享频道页均有 `id="main-content"` 且可接收程序化焦点。
- [ ] Playwright 验证链接聚焦可见、激活后 URL fragment 与焦点目标正确。
- [ ] RED/GREEN、lint、TypeScript、format:check、production build 通过。

## Blocked by
- #82 — skip link 的首页目标位于该分支新增的首页结构。
