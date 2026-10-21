# 统一 API 错误契约规格

## 目标

让 `kuros-backend` 与 `kuros-user` 对所有应用层 4xx/5xx 响应返回同一条可解析的错误信封，并消除资源不存在错误的错误码猜测。

本切片解决审计项 M1：资源异常默认标记为 `POST_NOT_FOUND`、坏 JSON 与参数绑定失败落入 Spring 默认响应、未捕获异常可能泄露实现细节且没有统一 `code`。

## 错误信封

```json
{
  "code": "COMMENT_NOT_FOUND",
  "message": "评论不存在",
  "fieldErrors": {
    "phone": "手机号格式不正确"
  }
}
```

- `code` 是稳定的机器可读错误码，客户端不得依赖 `message` 判断类型。
- `message` 是可直接展示给用户的中文说明。
- `fieldErrors` 只在存在字段级校验结果时返回；普通错误省略该字段。
- 未捕获异常只返回通用 `INTERNAL_ERROR`，不得把异常消息或堆栈返回客户端。

## 状态码与错误码

| 场景 | HTTP | code |
| --- | --- | --- |
| 请求体无法解析、参数类型错误或缺少参数 | 400 | `INVALID_REQUEST` |
| Bean Validation 字段校验失败 | 400 | `VALIDATION_FAILED` |
| 上传请求超过容器上限 | 413 | `UPLOAD_TOO_LARGE` |
| 未匹配路由或框架级静态资源不存在 | 404 | `NOT_FOUND` |
| 请求方法不受支持 | 405 | `METHOD_NOT_ALLOWED` |
| 请求内容类型不受支持 | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 请求响应类型不受支持 | 406 | `NOT_ACCEPTABLE` |
| 未捕获的服务端异常 | 500 | `INTERNAL_ERROR` |

业务校验继续使用现有细分错误码（例如 `POST_TITLE_REQUIRED`、`REPORT_DUPLICATE`），不改语义。

资源不存在错误码由抛出点明确提供：

- 帖子：`POST_NOT_FOUND`
- 评论：`COMMENT_NOT_FOUND`
- 媒体资源：`MEDIA_NOT_FOUND`
- 举报记录：`REPORT_NOT_FOUND`
- 用户：`USER_NOT_FOUND`
- 已有角色、声骸和资讯继续使用各自的领域错误码

`ResourceNotFoundException` 必须要求错误码参数。代码中不再保留默认映射为 `POST_NOT_FOUND` 的构造器，使新增资源类型时由编译器强制选择错误码。

## 容错边界

- 参数绑定、坏 JSON 与上传上限错误由 `@RestControllerAdvice` 统一转为错误信封。
- 未被更具体 handler 命中的异常由通用 handler 记录 `error` 级日志并返回 500。
- 已存在的 SaToken、业务、权限、降级和存储错误 handler 保持原有语义。
- 两个服务继续各自维护 handler；本仓库已有“按纪律复制、不建 common 模块”的架构决策。

## 验收标准

1. backend 与 user 的畸形 JSON 请求均返回 HTTP 400、`code=INVALID_REQUEST`，且响应是错误信封。
2. backend 的查询参数类型错误返回 HTTP 400、`code=INVALID_REQUEST`。
3. 删除不存在评论返回 HTTP 404、`code=COMMENT_NOT_FOUND`，不再返回 `POST_NOT_FOUND`。
4. 两个服务的通用异常 handler 返回 HTTP 500、`code=INTERNAL_ERROR`，不包含内部异常消息。
5. `ResourceNotFoundException` 无默认错误码构造器；所有 backend 调用点显式提供资源码。
6. 普通错误不再混用 `fieldErrors=null` 与空对象两种表示。
7. Compose 冒烟发起超过容器请求上限的上传，并断言 HTTP 413、`code=UPLOAD_TOO_LARGE`。
8. 定向单元/集成测试、`test-compile` 和相关鉴权回归通过。

## 非目标

- 不引入跨服务 common 模块。
- 不重写现有业务错误码，也不把所有 400 合并为单一错误码。
- 不新增 API 字段校验框架或改变现有请求字段语义。
- 不改变 401、403、404、429、503 等既有业务状态和前端展示。
