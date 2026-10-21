# 31. Gateway Nacos 测试容器资源边界

## 架构迁移全景

Gateway 的服务发现集成测试使用真实 Nacos v3.1.1 Testcontainer，验证网关自身注册，以及 `lb://kuros-backend` 经 Nacos 转发到桩后端。本次没有修改生产代码，只修复测试基础设施的三个边界：固定端口映射必须同时声明容器暴露端口；Nacos 和 Surefire 的 JVM 堆必须受控；测试类使用独立 JVM，避免 Nacos 客户端、Reactor 和 Spring context 的静态状态互相污染。

## 关键难点解析

- **固定端口不等于暴露端口**：Testcontainers 根据 `withExposedPorts` 登记等待策略要检查的端口。只在 Docker 创建参数里绑定 `28848/29848`，HTTP 等待策略会报 `Target port 29848 is not exposed`，容器尚未就绪时测试已经失败。
- **Nacos 默认堆与测试需求不匹配**：服务发现断言不需要 Nacos 的默认 2 GiB 堆。镜像内进程收敛为 256 MiB 初始、512 MiB 上限，堆外预留同时收敛，给 Surefire 与 Docker VM 留出内存。
- **Surefire 也必须受限**：Windows 宿主机只有 16 GiB，Docker VM 约占 7.5 GiB。一次定向测试曾因测试 JVM 默认堆上限约 4 GiB、220 个线程叠加 Nacos 容器而原生 OOM；验证命令将测试 JVM 限制为 768 MiB 堆、4 个处理器、512 KiB 栈。
- **全量共用 JVM 仍会引入顺序相关波动**：最终集成树的首次全量运行中，Nacos 容器在 120 秒后未就绪，而同树定向测试随后 2/2 通过。Gateway 采用与 backend/user 相同的 `reuseForks=false`，让三个测试类各自启动受限 JVM，串行隔离容器、Reactor 与 Spring 上下文。
- **测试必须有真实外部状态**：测试断言 Nacos OpenAPI 返回健康实例，并通过真实 HTTP 请求验证网关路由和桩后端收到的路径、查询串。它没有把服务发现替换成 mock。
- **本机临时目录影响 JDK 网络初始化**：Codex 沙箱注入的 `TEMP/TMP` 曾使 JDK 在 Windows 上无法建立内部回环连接。本次验证把 `TEMP/TMP` 和 `java.io.tmpdir` 指向项目工作区；该处理属于运行环境隔离，不进入生产配置。

## 简历 STAR 写法

- **S**：Gateway 集成测试使用真实 Nacos 容器，但测试在 CI/本地出现端口未暴露和 JVM 原生 OOM，服务发现链路实际上没有稳定回归证据。
- **T**：在不降低测试真实性的前提下，让单容器集成测试在 16 GiB 开发机上可重复执行。
- **A**：补齐 Testcontainers 暴露端口，收敛 Nacos 镜像内部 JVM 参数，以受限且按测试类隔离的 Surefire JVM 串行运行定向与全量测试，并固定临时目录排除宿主机环境干扰。
- **R**：定向测试 2/2、Gateway 全量 11/11 通过；Nacos 启动约 12 秒，真实注册和 `lb://` 路由断言均通过。

## 原理详解

```text
Surefire test classes (one bounded JVM per class, serially)
  -> Nacos discovery test JVM (max 768 MiB)
       -> starts Testcontainers Ryuk
       -> starts Nacos container (max 512 MiB heap)
            API 8848  -> host 28848
            gRPC 9848 -> host 29848
       -> Gateway registers kuros-gateway into Nacos
       -> test registers kuros-backend stub into Nacos
       -> HTTP request to Gateway
            -> lb://kuros-backend
            -> Nacos discovery
            -> stub backend -> response assertions
```

`withExposedPorts` 建立容器端口的元数据，`withCreateContainerCmdModifier` 建立固定宿主端口绑定。Nacos 客户端按 API 端口推导 gRPC 端口，因此两个端口都固定，并保持 1000 的偏移。`reuseForks=false` 不改变测试断言或业务状态，只把测试类间的 JVM 级全局状态隔离。资源上限不改变协议或数据格式，只改变测试进程可使用的内存。

## 面试八股文整理

1. **为什么使用 Testcontainers 而不是 mock Nacos？** 目标是验证注册、发现、端口推导和真实 HTTP 转发能否连接起来；客户端 mock 只能证明调用形状。
2. **为什么固定宿主端口？** Nacos 客户端从服务端地址推导 gRPC 端口为 API 端口加 1000。随机映射会破坏偏移，测试必须固定一对端口并保证并发隔离。
3. **为什么还要设置容器内存？** JVM 堆上限是允许使用量，不是实际启动量。多个默认大堆同时运行会把宿主机提交内存推高，最终出现容器不启动或测试进程原生崩溃。
4. **为什么要限制 `ActiveProcessorCount`？** Netty、Nacos 和 JVM 的线程池会跟随处理器数量扩张。测试只需要少量并发，限制处理器数可避免线程栈和调度资源浪费。
5. **RED 和 GREEN 分别证明了什么？** RED 证明现有测试基础设施无法启动真实 Nacos；GREEN 证明修复后注册与路由断言通过，而不是通过禁用容器得到假绿。

## 技术选型对比

| 方案 | 优点 | 缺点 | 本项目结论 |
|---|---|---|---|
| 真实 Nacos + 受限 Testcontainer | 覆盖完整发现链路，资源可预测 | 启动仍需约 12 秒 | 采用 |
| Mock Nacos client | 快 | 无法验证端口、协议和真实注册 | 不采用 |
| 随机端口映射 | Testcontainers 默认简单 | 破坏 API/gRPC 端口偏移 | 不采用 |
| 固定端口但只配置 Docker port binding | 看似可用 | 等待策略找不到暴露端口，测试提前失败 | 不采用 |
| 容器与 Surefire 使用默认 JVM 大小 | 无额外参数 | 16 GiB 开发机容易同时提交过多内存 | 不采用 |
| 所有测试类复用同一受限 JVM | 启动快 | Nacos/Reactor/Spring 全局状态可能产生顺序相关波动 | 不采用 |

## 面试叙事模板

**30 秒版**：Gateway 原先的 Nacos 集成测试没有真正稳定跑通。我补了 Testcontainers 的暴露端口声明，并把 Nacos 和服务测试 JVM 的堆限制在可预测范围内。修复后真实注册和负载均衡路由共 11 条 Gateway 测试全部通过。

**2 分钟版**：这个测试用真实 Nacos 容器验证 Gateway 注册和 `lb://` 路由。第一层问题是固定端口映射只写了 Docker binding，没有写容器暴露端口，Testcontainers 的 HTTP wait 找不到目标端口；补上 `8848/9848` 后容器能启动。第二层问题是 Nacos 镜像默认 2 GiB 堆叠加 Surefire JVM 默认约 4 GiB 上限，在 16 GiB Windows 开发机上触发原生 OOM。我把 Nacos 限到 512 MiB 堆，把测试 JVM 限到 768 MiB，并限制处理器、栈和直接内存。最终集成树首次全量运行时，共享 JVM 仍出现一次 Nacos 启动波动，而定向测试随后通过；因此我又按测试类隔离 JVM。最终定向 2 条、全量 11 条通过，测试仍然使用真实注册中心并检查实际 HTTP 路由结果。
