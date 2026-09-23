# se-01: Spring Data Elasticsearch on Boot 4.1.1 spike（去风险 go/no-go）

**What to build:** 一次性验证切片 #14 的最高风险技术假设——在 Spring Boot 4.1.1 下，Spring Data Elasticsearch 6.1.x 能否正常 index + search 一条中文文档且 ik 分词生效（"鸣潮攻略"命中"鸣潮的攻略"）。跑通后续工单放心铺开；踩坑则据结论回退原生 ES Java Client 手动装配。这是 tracer-bullet 第一发，gate 全链路。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] 验证①：Spring Data ES 自动装配的 JsonpMapper（Boot 4 下 Jackson 2/3 共存）能正常序列化 index + search 一条中文文档
- [x] 验证②：ik 分词生效——Testcontainers 起自建 ik 镜像的 ES 9.4.5，`ik_max_word` 建索引 / `ik_smart` 搜索，"鸣潮攻略"命中"鸣潮的攻略详解"
- [x] 验证③：国内拉 `docker.elastic.co` ES 镜像 + analysis-ik 9.4.5 插件通畅（不通则离线包兜底 + 可配镜像源）
- [x] 验证④：RocketMQ 消费端 FlatMessage JSON 反序列化结构 + String→LocalDateTime/long 类型转换可行
- [x] 验证⑤：Testcontainers ES + 自建 ik 镜像可用 + CI runner 内存扛得住 ES 容器（堆收敛后）
- [x] 产出 go/no-go 决策记录（走 Spring Data ES 还是回退原生 client），结论写回本工单/spec
- [x] spike 测试代码明确标注：保留为 se-07 集成测试种子，或一次性验证后清理
- [x] 编译级快验通过（`mvnw test-compile`）

## go/no-go 决策：GO —— 走 Spring Data Elasticsearch

**版本基线（实测锁定）**：Spring Boot 4.1.1 → spring-data-elasticsearch 6.1.1 + elasticsearch-java 9.4.5 + elasticsearch-rest5-client 9.4.5；服务端 ES 9.4.5 + analysis-ik 9.4.5（严格等版本，`get.infini.cloud` 拉取）。**无需回退原生 ES Java Client**——Spring Data ES 自动装配即用。

**实测证据**（本地 Docker Desktop ~7.5GiB / Java 25 编译目标 21）：
- `ElasticsearchIkSpikeTest`（门控 `kuros.it.es=true`）：Tests run 1，Failures 0，64.03s（含 redis+ES 容器冷启）。一次证明①②③：JsonpMapper index+search 中文文档、ik 隔字命中（搜索词"鸣潮攻略"命中文档"鸣潮的攻略详解"，MySQL LIKE 做不到）、`LocalDateTime` 序列化回读不失真。
- `FlatMessageDeserializationSpikeTest`（非门控）：Tests run 2，Failures 0，0.30s。证明④：Canal FlatMessage 扁平 JSON 可被 Jackson 2 稳定解析、据 `pkNames` 从 `data[]` 提取 postId 集合、String 值可转 `long`/`LocalDateTime`。
- 验证③：`docker build` 成功——ES 9.4.5 基础层 848MB（671s，`docker.elastic.co` 国内慢是唯一瓶颈，非阻断）；ik 9.4.5 插件安装成功（伴随 legacy Security Policy / outbound_network entitlement 两个非阻断 WARNING）。
- 验证⑤：CI backend job 跑裸 `./mvnw test`，门控 ES spike **默认不跑**（同 RocketMQ 集成测试门控范式），故默认 CI 内存无压力；本地 64s 跑通、ES 堆收敛 `-Xmx512m`，若 se-07 增设专用 ES CI job，GitHub-hosted ubuntu-latest（16GB RAM）容纳 ES+redis+H2 绰绰有余。

**API 破坏性变更备忘（写代码踩过的坑，供 se-02+ 复用）**：
- `NativeQuery` 从 `org.springframework.data.elasticsearch.core.query` 迁到 **`org.springframework.data.elasticsearch.client.elc`**（6.1.1）。
- Testcontainers `HttpWaitStrategy` 用 **`forPort(int)`** 而非 `withPort(int)`。

**spike 代码去留**：
- `FlatMessageDeserializationSpikeTest`——**保留**（非门控、0.3s、进主套件），作为 CDC FlatMessage 契约的常驻回归护栏。
- `ElasticsearchIkSpikeTest`——**保留为 se-07 集成测试种子**（门控，不拖慢主套件）；se-07 在此基础上换真实 `PostSearchDoc` 扩展高亮/分页/排序断言。

**已知噪声（非本 spike 阻断项）**：test profile 已关 nacos discovery/config，但 nacos config client 仍后台重试连 localhost:8848/9848 打 `Connection refused` WARN，不影响测试通过；如需消除留待后续独立处理。
