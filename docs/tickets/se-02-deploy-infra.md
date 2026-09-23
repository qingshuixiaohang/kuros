# se-02: 部署基建（compose ES+ik / canal-server / MySQL binlog）

**What to build:** 把切片 #14 的运行环境立起来——`docker compose up` 后 ES 9.4.5（带 ik 分词）+ canal-server 1.1.8 + MySQL（开 binlog）就绪，backend 能连上 ES（软依赖，ES 挂了不阻断主链路启动）。这是搜索轨与 CDC 轨共同的基建底座。

**Blocked by:** se-01（spike 确认 ES+ik 镜像方案与版本基线可用）

**Status:** ready-for-agent

- [ ] compose 新增 ES 9.4.5 服务：自建带 ik 的 Dockerfile（`docker.elastic.co` 基础镜像 + `elasticsearch-plugin install analysis-ik 9.4.5` 严格等版本），单机、`xpack.security.enabled=false`、堆收敛 `-Xms512m -Xmx512m` 防 OOM
- [ ] ES 镜像国内拉取兜底：离线包 / 可配镜像源（对齐 `quay.io/minio` 风格）
- [ ] compose 新增 canal-server v1.1.8 服务（依赖 mysql healthy + rocketmq-broker healthy）
- [ ] compose 新增 Kibana（观测面，用 profile 隔离，CI 不启）；拒 canal-admin
- [ ] MySQL command 补 binlog 参数：`--server-id=1 --log-bin=mysql-bin --binlog-format=ROW --binlog-row-image=FULL`
- [ ] `docker/mysql-init` 加 canal 复制账号（`SELECT, REPLICATION SLAVE, REPLICATION CLIENT`）
- [ ] backend pom 加 `spring-boot-starter-data-elasticsearch`（BOM 托管 6.1.x）+ ES 连接配置（软依赖 `required:false`，ES 不可用不阻断启动）
- [ ] 端口分配不冲突（ES 9200/9300、canal 11111、Kibana 5601）
- [ ] 编译级快验通过（`compose config`、`mvnw test-compile`）；ES `_cat/plugins` 显示 analysis-ik；现有测试不破坏
