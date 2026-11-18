# 修复 Gateway Nacos 集成测试资源边界

## 问题

Gateway 的 Nacos 服务发现集成测试无法稳定执行：Testcontainers 没有登记固定映射端口，
导致 HTTP wait strategy 提前失败；Nacos 与 Surefire 的默认大堆在 16 GiB 开发机会触发
原生 OOM；共享测试 JVM 还会出现顺序相关的容器启动波动。

## 交付

- 显式暴露 Nacos API 8848 与 gRPC 9848 容器端口。
- 限制 Nacos 容器和 Gateway Surefire JVM 的内存。
- 每个测试类使用独立受限 JVM，隔离 JVM 级静态状态。
- 记录资源边界 retro 和验证证据。

## 验收

- 真实 Nacos 定向测试 2/2 通过。
- Gateway 全量测试连续两轮 11/11 通过。
- 最终集成树 Gateway 全量 11/11 通过。
- PR 手动 CI 全绿后合并。
