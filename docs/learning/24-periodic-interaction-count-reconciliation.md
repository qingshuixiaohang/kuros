# 24 · 定期互动计数校准

> 对应 Issue：[#139](https://github.com/qingshuixiaohang/kuros/issues/139)

## 问题与交付

现有单帖 reconciler 能修复已知帖子，但没有周期任务发现异步投影失败后的数据库计数偏差。本次增加每日扫描已发布帖子的 job，按 ID 游标分批处理，并用 Redis 全局锁协调多个后端实例。

扫描保留 Redis 当前实时计数，并用它修复数据库冗余计数列。Redis key 缺失时才从关系表计数回填。直接用关系表快照覆盖已有 Redis 值会回滚正在等待 RocketMQ 投影的互动，因此定时路径和手动已知帖修复路径继续分开。

## 并发边界

- 数据库互动计数带单调递增的 `interaction_count_version`。LIKE/UNLIKE/FAVORITE/UNFAVORITE 投影每次实际更新计数都会推进版本。
- 定期 job 读取数据库版本后，以版本条件更新计数。版本不同表示期间已有投影提交，扫描放弃这次写入；这也能识别计数值经历 `0 → 1 → 0` 的 ABA 变化。
- JPA 常规实体保存不写版本列，版本只由计数投影和明确的计数修复 SQL 推进。
- Redis watchdog 明确报告续期丢失或异常。扫描在下一条帖子前停止，避免持锁失效后继续扫完整个目录。

## 验证

- `mvnw -Dtest=InteractionCountReconciliationJobTest,DistributedLockOwnershipTest test`：5/5 通过。
- `mvnw -DskipTests test-compile`：通过。
- `InteractionCountReconcileIntegrationTest` 的分页修复、异步投影等待和 ABA 集成用例已编译；本机执行被既有 `UserDirectoryStub` 静态初始化中的 JDK loopback pipe 限制阻断，尚未进入 Testcontainers。提权后 Docker Desktop 可访问（29.8.2），该失败与 Docker 镜像或 daemon 无关；CI 需运行这些集成用例。
- 首次全量 CI run `39408664347` 在 Compose 启动时发现两个版本为 V10 的迁移：既有 `V10__drop_user_domain_tables.sql` 与本次新增迁移冲突。新列迁移顺延到 V15；修复后的 CI 尚待重跑。另一个 user service job 因 Maven Central 下载 Maven 3.9.16 失败，与本次改动无关。

## 复盘

- 数据库关系表是投影持久化事实，但不代表最新实时互动。异步架构中的修复任务要区分“在线读源”和“投影落库源”，不能用一致性较慢的快照覆盖在线状态。
- 计数值本身不能充当并发版本；ABA 变化会让值比较 CAS 误判为无变化。新增公共版本字段时，需要让所有写路径推进它，并防止普通 ORM 保存覆盖它。
- 看门狗续期返回失败和抛出异常都代表任务不能继续假设持有互斥。长任务应接收 ownership 信号，并在安全边界停止。
- Docker 预检要区分 CLI 对 Docker Desktop 命名管道的访问权限与 daemon 是否启动；本机 Loopback 限制则应从 Testcontainers 前置桩的启动栈中确认。
- 不能只按 migration 目录中已知的主序列分配版本；服务拆分留下的迁移也占用全局版本号。提交前应对完整迁移目录检查重复版本，并以应用启动/Compose CI 验证 packaged artifact 的最终迁移解析。
