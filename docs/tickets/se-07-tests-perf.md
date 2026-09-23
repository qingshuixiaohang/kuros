# se-07: 测试 + perf 收口（S1~S4）

**What to build:** 用可复现的测试与真实性能数据证明切片 #14 解决了难点——搜索功能正确性、CDC 端到端同步、LIKE vs ES 的性能对比。禁止编造 TPS/RT 数字，只用实测数据回填 `docs/learning/14`。

**Blocked by:** se-03, se-04, se-05, se-06

**Status:** done

- [x] S1 搜索 HTTP 契约集成测试（Testcontainers ES 自建 ik 镜像 + H2）：分词命中/多字段 boost/高亮/过滤/三排序/search_after 深翻/CursorPageResult 契约/ES 降级
- [x] S2 CDC 索引写入集成测试：seed→index→断言 ES 文档、全量重建、DELETED 过滤、恢复可搜、幂等
- [x] S3 compose 端到端冒烟（新增 `search-cdc-smoke.mjs`）：发帖→轮询 `/search` 命中→记录端到端延迟→改帖/删帖验证同步；进 CI deployment job（ES 堆收敛 + Kibana 不启）
- [x] S4 门控 perf（`@EnabledIfSystemProperty(perf=true)`）：`LIKE '%x%'` vs ES 查询沿规模扫描耗时对比 + CDC 端到端延迟实测，`[PERF]` 前缀输出真实数据；默认不进 CI
- [x] 主套件兼容：不破坏现有测试；ES 集成测试用独立 H2 库名 + 每类独立 JVM（`reuseForks=false`）
- [x] 编译级快验通过；耗时测试（全量 `mvn test` / Testcontainers / compose 冒烟 / perf）切片 #14 已授权 AI 全程自跑

---

## 结果（实测数据，禁止编造 · 2026-09-23 采集）

### S1/S2 — 已在 se-03/se-04/se-05 建成（本工单核实并复跑）
- **S1 搜索 HTTP 契约**：`SearchHttpContractIntegrationTest`（分词命中/多字段 boost/高亮/status 过滤/三排序/search_after 深翻/CursorPageResult 契约）+ `SearchUnavailableIntegrationTest`（ES 降级 503）。门控 `-Dkuros.it.es=true`，se-04 提交时 11/11 green。
- **S2 CDC 索引写入**：`PostIndexServiceIntegrationTest`（seed→index→断言 ES 文档/全量重建/DELETED 过滤/恢复可搜/幂等，门控 5/5 green，se-03）+ `PostCdcHandlerTest`（FlatMessage→postId 集合→index/delete 分发，纯单测 8/8 green，se-05）。
- 二者均用独立 H2 库名 + `reuseForks=false` 每类独立 JVM，与主套件隔离。

### S3 — compose 端到端冒烟（本工单新增 `scripts/search-cdc-smoke.mjs` + 接入 ci.yml deployment job）
真实全链路（登录→发帖→MySQL binlog→canal 伪装 slave→RocketMQ `kuros-post-cdc`(FlatMessage)→backend `postCdcConsumer`→`PostCdcHandler` 回源组装→`PostIndexService.index`→ES→`GET /api/v1/search` 命中）。**CDC 端到端延迟实测**（全栈 compose，轮询间隔 500ms，postId=49a06750）：

| 阶段 | 延迟 | 轮询次数 |
|---|---|---|
| 发帖→可搜（索引） | **1853 ms** | 4 |
| 改帖→搜到新内容（更新） | **569 ms** | 2 |
| 逻辑删→搜索消失（删除同步） | **1087 ms** | 3 |

发帖→可搜首次略高（含 canal 冷启动后首条消息 + `ensureAlias` 懒创建索引/别名的一次性开销）；改帖/删帖走已建好的别名，收敛更快。全生命周期同步正确（postId 全链路一致）。

### S4 — 门控 perf（本工单新增 `SearchPerfBenchmark`，`-Dperf=true -Dkuros.it.es=true`，默认不进 CI）
`LIKE '%x%'` 前导通配符全表扫 vs ES ik 倒排索引，**沿规模扫描打表**（Testcontainers ES(ik) + H2，每点 repeat=20 取均值，keyword=鸣潮攻略，pageSize=20）：

| scale(posts) | LIKE avg(ms) | ES avg(ms) | LIKE/ES |
|---|---|---|---|
| 2000 | 13.166 | 37.303 | **0.35x**（小规模 ES 固定查询开销占优，反而慢） |
| 10000 | 14.425 | 16.487 | **0.87x**（交叉点） |
| 50000 | 66.726 | 15.823 | **4.22x**（ES 快 4.2 倍） |

- **LIKE 随 N 近线性上涨**：10k→50k 数据量 5x，LIKE 14.4→66.7ms 约 4.6x（前导通配符无法走 B+Tree 索引，退化为 O(n) 全表扫）。
- **ES 近乎恒定**：~16ms 不随总量涨（ik 分词建倒排索引，查询复杂度与语料规模解耦）。
- **隔字召回**（N=5000，标题「鸣潮的攻略详解」搜「鸣潮攻略」）：LIKE 命中=false（子串不连续漏召回），ES ik=true 且排首位（分词后 term 匹配 + boost 打分）。
- 诚实呈现交叉点：小规模下 ES 因网络往返 + 固定查询开销反而略慢，过交叉点后被越拉越开——这正是「为什么需要 ES」的量化依据，而非无条件吹 ES。Tests run 2 / Failures 0（规模扫描 + 隔字召回）。

### 主套件兼容 — 全量 `mvn test` 实测 **BUILD SUCCESS：Tests run 110 / Failures 0 / Errors 0 / Skipped 19**（08:22 min，MVN_EXIT=0）
与 se-05e 基线 110/0/19 完全一致，se-06（前端）+ se-07（门控 perf + 冒烟脚本）未新增任何常开后端测试，零回归。19 skipped = 门控测试（ES 集成/perf/spike，需 `-D` 开关，CI 裸 `mvn test` 不跑）。

### 编译级快验
- 后端：全量 `mvn test` 已含 test-compile，BUILD SUCCESS。
- 冒烟脚本：`node --check scripts/search-cdc-smoke.mjs` 通过。

### ⚠️ vibe learning — canal→RocketMQ producer NPE（CDC 静默不通的致命踩坑）
**现象**：全栈起好后 `GET /api/v1/search` 恒 503，ES 无 `post_search` 索引/别名；RocketMQ 无 `kuros-post-cdc` topic；backend 消费者已启动却收不到任何消息。CDC 端到端**从未打通**，但所有单测/门控集成测试全绿（测不出——单测直接喂 FlatMessage 给 handler，绕过了真实 canal producer）。

**定位**：canal 应用日志写在容器内文件不写 stdout（`docker compose logs` 只见 wrapper 脚本），须 `docker compose exec -T canal-server` 读 `/home/admin/canal-server/logs/kuros/kuros.log`——刷了 **1503 次** `ERROR CanalRocketMQProducer - null / NullPointerException at CanalRocketMQProducer.send:242`。

**根因**（canal-1.1.8 源码 `CanalRocketMQProducer.send`）：配了 `flatMessage=true` + `partitionHash` 非空 → 命中第 234-258 行「分区合并」分支；第 242 行 `for (int i = 0; i < partitionNum; i++)` 的 `partitionNum` 是装箱 `Integer`，经三级回退取值（`parseDynamicTopicPartition`→null；`getTopicDynamicQueuesSize`（需 `enableDynamicQueuePartition`，未开）→null；`destination.getPartitionsNum()`=`canal.mq.partitionsNum`，**未配**→null）。三者全 null，循环条件自动拆箱即 NPE——每条 binlog 事件发送都崩、topic 永不创建、CDC 全程静默不通。

**修复**：`compose.yml` canal-server env 显式声明 `canal.mq.partitionsNum: "4"`（对齐 `broker.conf` 的 `defaultTopicQueueNums=4`：partitionHash 把同一 postId 稳定哈希到 4 队列之一保序，且分区数 ≤ topic 队列数才不会发到不存在的队列）。重建 canal-server 后 NPE=0、`kuros-post-cdc` topic 建好、首条 CDC 写入经 `ensureAlias` 懒创建 `post_search_v1`+别名，冒烟三步全绿。

**教训**：(1) 真实中间件链路（canal/MQ）的 bug 单测测不出，S3 端到端冒烟是不可替代的一层——这正是本工单 S3 的价值。(2) canal 日志不落 stdout，排查须进容器读文件。(3) FlatMessage + partitionHash 组合下 `partitionsNum` 是**必填项**而非可选，canal 文档未明示，只在源码里暴露。

### 附带修复 — 冒烟脚本 503 容忍（两处）
- **门禁**：全新 ES 上 `post_search` 别名尚未由首条 CDC 写入懒创建，`/search` 先 503（se-04 刻意设计：索引异常一律降级“暂不可用”，不静默空、不 500）。脚本前置门禁改为容忍初始 503（非 503 才当真故障早失败）。
- **pollUntil 谓词**：`searchHits` 加 `tolerateUnavailable` 参数；第①②步（发帖/改帖→可搜）传 `true`，把轮询窗口期的 503 当“暂无命中”返回空数组继续轮询，避免谓词抛异常中止轮询（实测踩坑：门禁修好了却漏了谓词，发帖成功后仍崩在第一次 poll）；第③步（删除校验）传 `false`——此时索引必已存在，503 是真故障应暴露，不能误判成“已删除”。
