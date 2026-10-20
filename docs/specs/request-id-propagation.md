# Gateway 到业务服务的请求 ID 关联

## 用户故事

作为维护者，我希望能用一个请求 ID 在 Gateway、backend 和 user 的日志中定位同一次 HTTP 请求及其 Feign 子调用，以便更快排查跨服务问题。

## 范围

- Gateway 为每个路由请求生成 UUID request ID，覆盖客户端传入的 `X-Request-Id`，转发并在响应回显。
- backend 和 user 接收规范 UUID；直连服务时若 header 缺失或非法则生成新 UUID。响应回显服务实际使用的 ID。
- backend/user 请求线程的 MDC 含 `requestId`，服务控制台日志输出该字段并确保请求结束后清理。
- backend 发往 user 的 Feign 调用传播当前 request ID。
- Gateway 记录包含 request ID、method、path、status 的请求完成日志，不记录 query string。

## 不做

- 不实现 W3C trace context、span、采样、OTel/Micrometer tracing/exporter、Prometheus 指标或仪表盘。
- 不改业务 API body、鉴权、路由、用户可见行为、数据库或消息事件契约。
- 不传播到定时任务、RocketMQ 消费等非 HTTP 同步调用链。

## 行为规格

1. Gateway 为 routed HTTP 请求生成新的 canonical UUID，并无条件覆盖入站 `X-Request-Id`，因此客户端无法控制日志关联键。
2. 同一 Gateway 请求的 downstream request header 和 client response header 值一致。
3. backend/user 仅接受 canonical UUID（8-4-4-4-12 十六进制格式）；无效值不进入日志或响应，而是换成新 UUID。
4. backend/user filter 在当前 Servlet 请求处理期间将已验证值放入 MDC `requestId`；正常返回和异常退出后均移除该 MDC 项。
5. backend 的 Feign 调用只在 MDC 有 requestId 时传播；无上下文时不发送空值 header。
6. Gateway 完成日志仅记录 requestId、method、path 和最终 HTTP status，不记录 URL query、Cookie 或原始客户端 header。
7. 响应体与既有状态码、路由和安全规则保持不变。

## 验收标准

- [ ] Gateway 集成测试覆盖输入伪造 ID 被覆盖、下游与响应 ID 一致、格式为 UUID。
- [ ] backend 和 user 测试覆盖合法/缺失/非法 ID 的选择、MDC 作用域与异常后清理。
- [ ] Feign HTTP 桩收到与当前请求 MDC 一致的 `X-Request-Id`；无 MDC 时没有该头。
- [ ] 三服务日志配置可输出 requestId 字段，不改其他日志字段或泄露 query/header。
- [ ] Gateway/backend/user 定向测试通过，三工程编译通过。
- [ ] 全量 CI 通过，变更范围内无 API/body、授权或路由回归。

## 验证计划

- TDD：先写 Gateway 端到端转发测试与 Servlet filter/Feign 传播测试，确认 RED，再实现最小过滤器和拦截器。
- 运行 Gateway、backend、user 的定向测试与 test-compile。
- Blast radius：检查 Gateway 所有路由/预检响应，backend 所有 Feign 调用、Spring Security/CSRF filter 顺序，user 所有公开/内部响应和异常退出路径。
