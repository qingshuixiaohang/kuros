# 0010. 为请求生成并传播关联 ID

日期：2026-10-20

状态：已接受

## 背景

Gateway、kuros-backend 和 kuros-user 暂无统一请求标识。一次浏览器请求经过网关、内容服务以及 backend → user 的 Feign 调用时，日志无法稳定关联。现有 Actuator/Prometheus 指标继续负责聚合观测；本决定只补充单请求日志关联，不引入分布式 trace/span 系统。

## 决策

例子：打开帖子列表时，Gateway 生成一个请求 ID，内容服务查帖子和经 Feign 请求作者资料时沿用它。拿到响应头里的 ID，就能在网关、内容服务和用户服务的日志里查这次请求。MDC 是日志框架给当前处理线程保存附加字段的位置，Servlet 请求结束时清掉这个值，避免线程服务下一次请求时沿用旧 ID。

1. 对经 Gateway 路由的每个请求生成 UUID 格式的 `X-Request-Id`。Gateway 不信任或复用客户端传入的同名头，始终覆盖它，降低日志伪造与注入风险。
2. Gateway 将该 ID 放入下游请求并在响应中回显。backend 和 user 服务对直接到达的请求只接受规范 UUID；缺失或格式错误时自行生成 UUID，并在响应中回显。
3. backend/user 的 Servlet filter 在请求处理期间将 ID 放入 MDC，结束时始终清除；控制台日志格式包含该 MDC 字段。
4. backend 的 Feign `RequestInterceptor` 把当前 MDC request ID 传给 kuros-user 的内部调用；无 HTTP 请求上下文时不添加该头。
5. Gateway 以显式字段记录一次请求完成日志（ID、HTTP method、path、status），不把 query string 或客户端原始 header 写进日志。
6. 三个服务各自定义 header/filter；不新增跨服务 common 模块，符合 ADR-0003 的服务边界决策。

## 不在本决策范围

- 不添加 Micrometer Tracing、OpenTelemetry SDK/exporter、W3C `traceparent`、span、采样或追踪后端。
- 不改 API 响应体、认证/授权、错误码、路由或业务行为。
- 不传播到当前未从 HTTP 请求同步触发的后台任务；后续若有需要，另行设计任务上下文载体。

## 验证

- Gateway 路由集成测试证明伪造的客户端 ID 被替换，且下游收到的 UUID 与响应回显一致。
- backend/user filter 测试覆盖合法、缺失、非法 ID，MDC 作用域和清理。
- backend Feign 桩测试证明内部请求携带与当前请求相同的 ID。
- [请求 ID 复盘与验证记录](../learning/26-request-id-propagation.md)。
