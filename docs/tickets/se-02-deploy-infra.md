# se-02: 部署基建（compose ES+ik / canal-server / MySQL binlog）

**What to build:** 把切片 #14 的运行环境立起来——`docker compose up` 后 ES 9.4.5（带 ik 分词）+ canal-server 1.1.8 + MySQL（开 binlog）就绪，backend 能连上 ES（软依赖，ES 挂了不阻断主链路启动）。这是搜索轨与 CDC 轨共同的基建底座。

**Blocked by:** se-01（spike 确认 ES+ik 镜像方案与版本基线可用）

**Status:** done

- [x] compose 新增 ES 9.4.5 服务：自建带 ik 的 Dockerfile（`docker.elastic.co` 基础镜像 + `elasticsearch-plugin install analysis-ik 9.4.5` 严格等版本），单机、`xpack.security.enabled=false`、堆收敛 `-Xms512m -Xmx512m` 防 OOM
- [x] ES 镜像国内拉取兜底：离线包 / 可配镜像源（对齐 `quay.io/minio` 风格）
- [x] compose 新增 canal-server v1.1.8 服务（依赖 mysql healthy + rocketmq-broker healthy）
- [x] compose 新增 Kibana（观测面，用 profile 隔离，CI 不启）；拒 canal-admin
- [x] MySQL command 补 binlog 参数：`--server-id=1 --log-bin=mysql-bin --binlog-format=ROW --binlog-row-image=FULL`
- [x] `docker/mysql-init` 加 canal 复制账号（`SELECT, REPLICATION SLAVE, REPLICATION CLIENT`）
- [x] backend pom 加 `spring-boot-starter-data-elasticsearch`（BOM 托管 6.1.x）+ ES 连接配置（软依赖 `required:false`，ES 不可用不阻断启动）
- [x] 端口分配不冲突（ES 9200/9300、canal 11111、Kibana 5601）
- [x] 编译级快验通过（`compose config`、`mvnw test-compile`）；ES `_cat/plugins` 显示 analysis-ik；现有测试不破坏

## 实测验证证据（AI 全程自跑，本地 Docker Desktop ~7.5GiB）

- **ES 服务**：`docker compose up -d elasticsearch` → healthy；`curl /_cat/plugins` 返回 `analysis-ik 9.4.5`；`_cluster/health` = green（单节点）。
- **Dockerfile ARG 作用域 bug（实测才暴露）**：初版把 `ARG IK_PLUGIN_URL` 声明在 `FROM` 之前，导致 RUN 阶段 `${IK_PLUGIN_URL}` 为空 → `elasticsearch-plugin install` 缺 URL 报 **exit 64**；修正为 FROM **之后**重新声明 ARG（`compose config` 语法校验拓不到，必须 `up --build` 实跑才暴露）。
- **MySQL binlog**：`SHOW VARIABLES` 实测 `log_bin=ON`、`binlog_format=ROW`、`binlog_row_image=FULL`、`server_id=1`。
- **canal 复制账号**：`50-canal-user.sql` 创建 `canal@% / mysql_native_password`（免 caching_sha2 公钥握手坑）；存量卷场景手动 apply 验证通过（全新卷由 entrypoint 自动跑，CI 即此路径）。
- **canal env 覆盖机制（官方 wiki + 实测双证）**：单独跑 canal 容器传 env，日志显示 `destination=kuros`、`address=mysql:3306`、`RocketmqRemoting connect rocketmq-namesrv:9876`——证明 canal-server **优先读 env 覆盖** canal.properties/instance.properties 所有 key（含全局 `canal.serverMode`），故无需挂载 100+ 键配置文件。
- **canal 连 MySQL + binlog dump（核心验收）**：`docker compose up -d canal-server` → healthy；kuros.log 实测：
  - `init table filter : ^kuros\.post_tags$|^kuros\.posts$`（filter.regex 转义点号被正确解析并锚定，YAML 平量单反斜杠生效）
  - `address = mysql/172.20.0.5:3306` + `load MySQL @@version_comment`（canal 账号认证成功，compose DNS 解析正常）
  - `find start position successfully, journalName=mysql-bin.000001,position=4,serverId=1 ... the next step is binlog dump`（定位 binlog 起点，开始 dump）
  - `AbstractCanalInstance - start successful....` + `CanalRocketMQProducer - ##Start RocketMQ producer##`（实例启动成功，serverMode=rocketMQ producer 已启），canal.log 无 ERROR。
- **canal 堆收敛**：镜像默认 `-Xms2g -Xmx3g`，用 env `JAVA_OPTS=-Xms256m -Xmx512m` 后置覆盖（JVM 对重复 -Xmx 取最后值），避免与全栈共存 OOM。
- **现有测试不破坏**：全量 `mvnw test` 实测 **Tests run: 86, Failures: 0, Errors: 0, Skipped: 3**（门控），BUILD SUCCESS / 11min——加 ES starter 后 Spring 上下文正常装配（ES 客户端懒连接 + 健康指示器已关）。
- **未运行时验证项**：Kibana 仅 `compose config` 语法校验（profile 隔离、CI 不启、纯观测面），未拉镜像实跑（docker.elastic.co 文量大且非关键路径）。
