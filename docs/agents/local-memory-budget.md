# 16GB 开发机验证预算

本机的瓶颈由系统当前可用内存决定；开始重任务前先检查空闲物理内存与可提交内存。
2026-10-22 实测曾仅剩约 0.9GB 可提交内存，释放其他应用后恢复到约 6GB。

- Maven 进程默认最大堆 384MB，由三个工程的 `.mvn/jvm.config` 提供。
- Surefire 每次只开一个测试 JVM，最大堆 768MB。测试类隔离仍保留。
- 本地每次只运行一个 Maven 工程；完成后再运行下一个。
- Next 开发服务使用 `NODE_OPTIONS=--max-old-space-size=1024`，Playwright 使用 `--workers=1`。
- 浏览器批次与 Maven 集成测试批次分开执行；先关闭自己启动的开发服务，再切到 JVM。
- 普通 HTTP 集成测试使用 H2 和一个 Redis Testcontainer，测试结束自动清理容器。
- Nacos、MinIO、RocketMQ 和 ES 只在对应定向测试需要时运行。
- 全栈 Compose 冒烟交给手动 CI；本机需要演示时按需求点名服务，避免无参数启动全栈。

短临时目录仍按 issue-tracker 约定设置为 `G:\kuros-web\.scratch\tmp`。
测试证据必须记录实际结果；不能把内存不足、网络或容器启动失败记作业务 RED。
