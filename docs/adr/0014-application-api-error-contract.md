# 0014. 用户服务和内容服务遵循同一应用层错误契约

状态：已接受（依据 API 错误契约切片补录）

## 背景

浏览器通过 Gateway 调用两个业务服务。若服务分别采用 Spring 默认错误和自定义业务错误，同一个前端就要按错误来源猜响应结构，资源不存在也可能收到错误的领域错误码。

## 决策

两个业务服务的应用层错误统一返回 `code`、`message`，并在有字段校验信息时带 `fieldErrors`。客户端按稳定的 `code` 判断错误，资源不存在由抛出点明确给出对应领域错误码。通用异常对外返回 `INTERNAL_ERROR`，服务端日志保留诊断信息。契约范围是两个业务服务处理的应用层错误。

例如删除一条不存在的评论时，HTTP 404 表示资源不存在，`COMMENT_NOT_FOUND` 进一步告诉前端缺的是评论，`message` 是显示给用户的提示。网关无法连接下游等错误需要在网关侧单独查看。

## 备选与代价

沿用框架默认响应省下服务端转译，却要把差异交给所有调用端处理。共享 handler 库可以集中修改，但会增加两个服务的构建与发布耦合。依据 [ADR 0003](0003-user-service-split.md) 的模块取舍，两个服务各自维护 handler，用同一契约和对应测试约束行为。变更契约时需要同时检查两侧与前端解析。

## 验证入口

[API 错误契约规格](../specs/api-error-contract.md)、[内容服务契约测试](../../kuros-backend/src/test/java/com/kuros/kurosbackend/shared/web/ApiExceptionHandlerContractTest.java)、[用户服务契约测试](../../kuros-user/src/test/java/com/kuros/kurosuser/shared/web/ApiExceptionHandlerContractTest.java)、[前端 API 客户端](../../front/src/lib/api.ts)。
