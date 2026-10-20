# 26. 三服务请求关联 ID

## 架构迁移全景

一次浏览器请求可能经过 Gateway、kuros-backend，再由 backend 同步调用 kuros-user。改动前三处日志没有共同键，排查只能按时间、路径和状态猜测是否属于同一请求。本切片增加单请求日志关联：Gateway 生成 UUID 格式的 `X-Request-Id`，业务服务在请求作用域写入 MDC，Feign 子调用继续传播同一个值。

该值只是日志关联 ID，不是 W3C trace、span 或采样系统。不同服务各自拥有过滤器和配置，没有新增跨服务 common 模块。

## 关键难点解析

- **不可信任客户端输入**：Gateway 始终覆盖入站 `X-Request-Id`。否则调用方可以复用固定值污染关联，或注入换行等内容影响日志。
- **直连也必须可用**：backend/user 在 header 缺失或非法时生成新 UUID，并把实际值回显到响应。
- **MDC 生命周期必须闭合**：过滤器在请求进入时写入，在 `finally` 中恢复此前值。异常路径也必须清理，否则线程复用会把旧 ID 带到下一请求。
- **传播发生在调用线程**：Feign 拦截器读取当前 MDC；没有 HTTP 上下文时不写空 header。空值 header 会制造“已传播”的假象。
- **日志只能记录安全字段**：Gateway 完成日志只保留 requestId、method、path、status。query、Cookie 和原始客户端 header 不进入日志。

## 简历 STAR 写法

- **S**：三个服务串联处理一个请求，故障排查缺少稳定的跨服务关联键；外部输入又不能直接信任。
- **T**：在不引入完整 tracing 基础设施的前提下，为同步 HTTP 请求和 Feign 子调用建立可检索、无日志注入风险的关联链路。
- **A**：Gateway 生成并覆盖 UUID；backend/user 校验或重建 ID 并绑定 MDC；Feign 从 MDC 读取并传播；测试覆盖伪造值、非法值、异常清理、真实 HTTP 桩和安全日志字段。
- **R**：PR #144 的建设性 CI 全绿；Backend 196 条测试、Gateway 11 条、User 46 条、Frontend 39 条及 Compose 全栈验证通过。

## 原理详解

```text
client
  -> Gateway: generate UUID, overwrite input, echo header
       -> backend: validate/regenerate, MDC scope, echo header
            -> Feign interceptor: read MDC, add X-Request-Id
                 -> user: validate/regenerate, MDC scope, echo header
```

日志框架通过 `requestId:%X{requestId}` 读取当前线程 MDC。Gateway 是响应式网关，不在 Reactor 全局上下文中传播 MDC，而是在完成日志中显式携带过滤器中生成的局部值。

## 面试八股文整理

1. **为什么不用 `traceparent`？** 这一批只需要同步请求日志关联，没有 span 树、采样和 exporter 需求。直接引入半套 tracing 会扩大依赖和排障面。
2. **为什么 Gateway 不沿用客户端 ID？** 客户端值不可信，固定或伪造 ID 会削弱关联准确性，并带来日志注入风险。
3. **为什么 backend/user 还要重新校验？** 服务可以被直接访问，也可能被其他调用方调用。入口层的保证不能替代服务自身边界校验。
4. **为什么 MDC 放在 `finally` 清理？** Web 线程会被复用；异常离开路径如果没有清理，后续请求可能带上旧 ID。
5. **为什么 Feign 只从 MDC 读？** 它复用 HTTP 请求内的同一关联值，不需要修改每个客户端方法，也不会把空上下文伪装成有效传播。
6. **这和 Metrics 有什么区别？** Metrics 聚合趋势，日志关联 ID 定位单次请求。二者互补，本切片没有修改 Actuator/Prometheus 指标语义。

## 技术选型对比

| 方案 | 优点 | 缺点 | 本项目结论 |
|---|---|---|---|
| 自定义 `X-Request-Id` | 依赖少、同步链路直观、测试成本低 | 没有 span/采样能力 | 采用 |
| Micrometer Tracing + OTel | 标准和生态完整 | 依赖、上下文桥接和测试面显著扩大 | 本批不采用 |
| 每个 Feign 方法手写 header 参数 | 显式 | 重复、易漏、业务接口被观测需求污染 | 采用统一拦截器替代 |
| 新增 common 模块共享过滤器 | 单份源码 | 破坏当前服务边界，三行逻辑换来跨服务构建耦合 | 各自实现并纳入兼容性测试 |

## 面试叙事模板

**30 秒版**：社区请求经过 Gateway 后还会跨 backend 和 user 服务，我在入口生成并覆盖请求 ID，两个业务服务把它绑定到 MDC，Feign 再向用户服务传播。这样日志能用同一个 ID 串起来，同时不信任客户端输入，也不把空上下文写成有效 header。

**2 分钟版**：我先确认这条链路只包含同步 HTTP 和 Feign，因此没有直接上 OTel，而是做了一个最小可验证的关联 ID。Gateway 生成 UUID 并覆盖伪造输入；直连业务服务会校验 UUID，非法值重新生成；过滤器在请求结束后恢复 MDC，异常路径也在 `finally` 清理。backend 到 user 的 Feign 拦截器从 MDC 读取值，没有上下文不发送空 header。最后用 Gateway 集成、Servlet filter、Feign HTTP 桩和日志字段测试覆盖。全量 CI 中 Backend 196 条、Gateway 11 条、User 46 条测试通过，Compose 全栈也继续通过。
