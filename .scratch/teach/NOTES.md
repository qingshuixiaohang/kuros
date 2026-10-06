# 教学工作笔记（AI 用）

- 用户自述 0 基础：解释要用大白话 + 类比，术语第一次出现必须解释；命令给完整可复制的 PowerShell 版本。
- 中文教学（MISSION.md 已记录）。
- 2026-10-03 分工：用户开 Docker Desktop / IDEA（可选）+ 点亮栈；AI 全包接口、数据库、冒烟、联调测试。课件 0001 顶部横幅已同步。
- CI 已改手动触发；不要再说"等 CI 跑完"，改说"我在本地验证"。
- AI 测试栈用 worktree `G:\_tmp\kuros-i56`（项目名 kuros-i56，卷与用户隔离），测完 `docker compose down` 收场，避免与用户自己的栈（端口 3000/8080/3307/6379/9200/11111 等）冲突。
- 已知环境事实：Docker Desktop 有时是关的（用户开）；本机 Docker Hub 的 minio/minio:RELEASE.2023-03-20 镜像有本地缓存，但 CI 新机器已拉不到（MinIO 撤出 Docker Hub）——本地教学不受影响。
- IDEA 对用户是"看代码/学习"工具，不是启动项目的必需品；不要把 IDEA 步骤塞进启动流程，避免增加负担。
- **内存红线（2026-10-03 实测踩坑）**：机器 16GB，Docker Desktop WSL2 默认拿走 7.5GB（中间件实际用 ~5.3GB），页面文件小（C 盘仅剩 3.7G）。**绝不同时裸启两个 JVM**——两个 Spring Boot 默认堆同时提交内存会触发 errno 1455 直接崩。裸跑必须串行 + 限堆：backend `-Xmx640m -XX:MaxMetaspaceSize=288m`、user `-Xmx512m/256m`、gateway `-Xmx384m/224m`（.run/ 配置已内置），Maven 进程本身再给 `MAVEN_OPTS=-Xmx192m`。先起 backend 等健康，再起 user。
- 开发模式栈已实测通过 compose-smoke（2026-10-03）：Nacos 注册、网关登录共享 Redis、Feign 回填、持久化全绿。
- **最小服务集原则（用户 2026-10-05 明确要求）**：AI 测试只起当前需要的服务——最小集 mysql/redis/nacos；ES/canal/rocketmq/MinIO/控制台按需追加；JVM 限堆串行（.run 已内置）。已写入长期记忆。
- **技能包已更新至 mattpocock/skills v1.3.1（2026-10-05）**：CONTEXT.md 更名 GLOSSARY.md（仓库已 git mv 迁移，15 处引用同步）；工作流新增第 7 步 retro；多工单并行可用 implement-spec；resolving-merge-conflicts 已废弃移除。
