# ERR-01 统一 API 错误契约

状态：ready-for-agent

依赖：无

规格 Issue：[#145](https://github.com/qingshuixiaohang/kuros/issues/145)

实现 Issue：[#146](https://github.com/qingshuixiaohang/kuros/issues/146)

规格：[`docs/specs/api-error-contract.md`](../specs/api-error-contract.md)

## 交付

- backend 与 user 的错误处理器统一处理坏 JSON、请求绑定错误、上传超限和未捕获异常。
- backend 的资源不存在错误码由调用点显式提供，并移除默认为 `POST_NOT_FOUND` 的构造器。
- 普通错误省略 `fieldErrors`，校验错误保留字段映射。
- API 文档记录错误信封、稳定错误码与资源码。

## TDD 验收

1. 先添加失败测试：user 坏 JSON、backend 参数类型错误、backend 坏 JSON、backend 缺失评论错误码、两个服务的通用异常信封。
2. 确认测试因当前默认响应或错误码错误而 RED。
3. 实现最小 handler、资源码和配置改动。
4. 确认定向测试 GREEN，再跑两个模块的 `test-compile` 和受影响回归。

## 已知限制

- 容器级上传上限无法由 MockMvc 完整复现；集成测试覆盖业务大小校验，CI/Compose 冒烟负责容器链路。
- user 服务当前唯一资源类型仍是用户，因此其 handler 保持 `USER_NOT_FOUND` 映射；本切片不移除该异常类。
