# 鸣潮社区 API 契约

## 1. 通用约定

- Base URL：`/api/v1`
- 请求和响应：`application/json; charset=UTF-8`
- 成功响应：`{"data": ..., "meta": ...}`
- 分页字段：`page` 从 1 开始，`pageSize` 服务端限制最大值，`totalItems` 和 `totalPages` 由服务端返回。
- 时间统一使用 ISO-8601 的本地日期时间字符串；前端负责展示格式化。
- 认证使用 HttpOnly Cookie `KUROS_SESSION`；跨域请求必须携带凭据。
- 除 GET/HEAD 外的请求必须先获取 CSRF Cookie，并在 `X-XSRF-TOKEN` 请求头中回传。

## 2. 错误响应

```json
{
  "code": "POST_TITLE_REQUIRED",
  "message": "请输入帖子标题",
  "details": {}
}
```

常用状态码：`400` 请求校验失败，`401` 未登录，`403` 无权限，`404` 资源不存在，`409` 状态冲突，`500` 服务端错误。前端不得依赖 message 判断业务分支，应优先使用 code。

## 3. 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/code` | 请求验证码，开发环境可返回 devCode |
| POST | `/auth/login` | 手机号和验证码登录，写入会话 Cookie |
| GET | `/auth/me` | 获取当前用户，未登录返回 401 |
| GET | `/auth/csrf` | 获取 CSRF Cookie |
| POST | `/auth/logout` | 注销当前会话，成功返回 204 |

## 4. 帖子

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/posts` | 分页查询公开帖子；支持 `page`、`pageSize`、`sort=latest/hot`、`category`、`tag`、`keyword` |
| GET | `/posts/{id}` | 查询公开帖子详情 |
| POST | `/posts` | 登录用户创建并发布帖子，成功返回 201；作者取当前会话 |

创建请求：

```json
{
  "type": "GUIDE",
  "category": "配队攻略",
  "title": "长离实战循环记录",
  "excerpt": "可选，缺省时由正文生成",
  "content": "## 先确定循环\n\n正文内容",
  "tags": ["长离", "实战"]
}
```

当前版本允许 `GUIDE` 和 `GENERAL` 两种类型；标题最多 200 字符，正文最多 50000 字符，最多 10 个标签。编辑、删除、草稿和审核接口留给后续版本。

## 5. 评论与互动

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/posts/{id}/comments` | 分页查询评论，支持 `sort=hot/latest` |
| POST | `/posts/{id}/comments` | 创建评论或一级回复 |
| DELETE | `/posts/{id}/comments/{commentId}` | 删除当前用户自己的评论并保留占位 |
| GET | `/posts/{id}/interactions` | 查询当前用户点赞/收藏状态与计数 |
| POST/DELETE | `/posts/{id}/interactions/like` | 点赞/取消点赞 |
| POST/DELETE | `/posts/{id}/interactions/favorite` | 收藏/取消收藏 |
| GET/POST/DELETE | `/users/{targetUserId}/follow` | 查询、关注或取消关注作者 |

## 6. 用户资料

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/users/{userId}` | 公开用户资料 |
| GET | `/users/{userId}/posts` | 公开帖子列表 |
| GET | `/users/me/profile` | 当前用户的帖子、评论、收藏和关注概览 |

## 7. 接口演进规则

数据库字段通过 Flyway 迁移演进；不在已发布迁移上直接修改。新增字段优先保持向后兼容，破坏性变化使用新版本路径或明确迁移窗口。前端 API 封装集中放在 `front/src/lib/api.ts`，组件不得自行拼接认证、CSRF 和错误解析逻辑。
