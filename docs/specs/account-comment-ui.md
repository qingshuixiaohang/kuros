# 账户入口与评论编辑器工具栏规格

## Problem Statement

桌面端顶部已登录账户区的头像、个人中心和退出操作缺少层级，连续文本让用户难以判断点击目标。帖子评论编辑器底部仍使用说明性文字，无法提供计划中的快捷操作。

## Solution

将账户区组织为头像锚点、个人中心主链接和退出次级按钮；将评论编辑器工具栏组织为三个语义明确的图标按钮。表情与提及直接作用于文本光标，图片按钮提供文件选择入口并明确提示评论附件接口尚未接入。

## User Stories

1. As a logged-in desktop user, I want to distinguish my profile entry from logout, so that I do not click the wrong account action.
2. As a commenter, I want familiar quick actions beside the comment box, so that I can insert an emoji or mention marker without typing it manually.
3. As a keyboard user, I want every account and composer action to have a name and focus state, so that I can complete the same flow without a mouse.

## Implementation Decisions

- 账户区继续使用共享 `TopNavigation`，不新增账户 API。
- 个人中心使用链接作为主操作，退出使用低强调按钮；头像继续保持鸣潮社区的文字头像，不引入外部 Logo。
- 表情按钮插入有限的常用表情面板，避免引入第三方编辑器依赖。
- 表情面板使用 `@floating-ui/react` 的 Portal、fixed 定位和碰撞检测；继续保留鸣潮主题的轻量自定义表情网格，不引入完整 emoji 编辑器。
- 表情面板必须脱离帖子内容的 `overflow` 层级，在滚动、窗口边界和移动视口变化时自动重新定位。
- @ 按钮在 textarea 当前光标位置插入 `@` 并恢复光标位置。
- 图片按钮使用隐藏文件选择器作为前端入口；由于评论 API 当前只有文本字段，不上传、不伪造附件结果，选择后给出明确提示。
- 三个按钮只改变编辑器交互，不改变评论请求的数据结构。

## Testing Decisions

- 先添加失败的浏览器测试，验证账户区操作名称、三个按钮和表情/@行为。
- 回归评论发布和回复入口，检查 1440px、1024px、390px 无横向滚动。
- 使用真实 Chromium 验证键盘可达性、焦点状态和图片选择提示。
- 验证表情面板通过 Portal 渲染、打开/关闭不阻塞 textarea，且在桌面端和窄视口不会被帖子容器裁剪。

## Out of Scope

- 评论图片上传、附件存储、图片预览和后端数据模型。
- 登录流程和退出接口改造。
- 移动端完整重设计。
