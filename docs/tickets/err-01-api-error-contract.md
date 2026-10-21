# ERR-01 统一 API 错误契约

状态：implemented（本地测试与 Compose 验证通过，待 CI 复验）

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
4. Compose 冒烟上传超过容器上限的文件，断言 413 `UPLOAD_TOO_LARGE`。
5. 确认定向测试 GREEN，再跑两个模块的 `test-compile` 和受影响回归。

## 已知限制

- user 服务当前唯一资源类型仍是用户，因此其 handler 保持 `USER_NOT_FOUND` 映射；本切片不移除该异常类。

## 验证记录

- RED：backend 契约 3 例为 2 failure + 1 error；user 契约 2 例为 1 failure + 1 error。
- GREEN：backend `ApiExceptionHandlerContractTest` 5/5、`KurosBackendApplicationTests` 41/41；user `ApiExceptionHandlerContractTest` 4/4、`AuthLoginIntegrationTest` 7/7。
- 两侧 `test-compile` 通过，`node --check scripts/compose-smoke.mjs` 和 `git diff --check` 通过。
- 在最小 Compose 栈（MySQL、Redis、Nacos、backend）发送 15 MB multipart 请求，实际返回 HTTP 413 与 `{"code":"UPLOAD_TOO_LARGE","message":"上传文件过大"}`。
