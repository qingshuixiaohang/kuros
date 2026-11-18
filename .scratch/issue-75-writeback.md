## 切片 #14 实现完成 — 交付回写（全文检索 + CDC 增量索引）

分支 `codex/slice-14-search-cdc`（10 commits，`8b74bd8..cceef39`），8 个 tracer-bullet 工单 se-01~08 全部收口。全部性能/延迟数字为**实测**，禁止编造。

### 交付内容（按工单）

| 工单 | 交付 | commit |
|---|---|---|
| se-01 | ES+ik spike go/no-go=**GO**（Boot 4.1.1 / Spring Data ES 6.1.1 / ES-ik 9.4.5，ik 分词生效、JsonpMapper 序列化不失真、FlatMessage 可解析） | d3e0dbe |
| se-02 | CDC 部署基建：ES(ik 自建镜像)+canal-server 1.1.8+MySQL binlog(ROW/FULL)+canal 复制账号+Kibana profile+backend ES 软依赖 | 9890638 |
| se-03 | `PostSearchDoc` mapping(ik_max_word/ik_smart+boost)+`PostIndexService` 回源组装/`_id=postId` 幂等 upsert/全量重建切别名+`SearchIndexManager` | fe8b64d |
| se-04 | `GET /api/v1/search`：multi_match+boost+高亮+过滤+三排序+search_after+ES 软依赖降级+SaToken 白名单 | 79aa9e3 |
| se-05 | CDC 消费者：`FlatMessage`+`PostCdcHandler`(→postId→index/delete)+`PostCdcStreamConfig` orderly 消费 | 4d62846 |
| se-06 | 前端搜索接入：`searchPosts`+`HighlightText` 安全高亮+无限滚动+503 降级（最小载体，不重做 UI） | 8f92741 |
| se-07 | S1~S4 测试+perf：`SearchPerfBenchmark`+`search-cdc-smoke.mjs`+CI deployment job+canal partitionsNum 修复 | 41bddad |
| — | code-review 修复：`SearchIndexBootstrap` 启动软失败引导+补 S2 恢复可搜测试 | 0a0045d |
| se-08 | docs/learning/14 八段式复盘+README 索引+本回写+PR | cceef39 |

### 测试结果（门控实测）

- **S1 搜索 HTTP 契约**：`SearchHttpContractIntegrationTest` 11/11 green（ik 分词命中/多字段 boost/高亮/status 过滤/三排序/search_after 深翻/CursorPageResult 契约/ES 降级 503）。
- **S2 CDC 索引写入**：`PostIndexServiceIntegrationTest` **7/7 green**（59.85s；回源组装/全量重建切别名/DELETED 过滤/幂等/物理删兜底 + code-review 补 2 例：恢复可搜、启动引导后搜索返空而非 503）+ `PostCdcHandlerTest` 8/8（纯单测）。
- **S4 perf**：`SearchPerfBenchmark` 2/2 green。
- **全量套件**：`mvn test` **110/0/19 BUILD SUCCESS**（08:22 min），与 se-05e 基线一致，**零回归**。19 skipped = 门控测试（CI 裸 `mvn test` 不跑）。
- **前端**：`lint` 0 error + `build` Compiled successfully（tsc 通过）。

### perf 实测（LIKE 全表扫 vs ES ik 倒排，规模扫描，repeat=20）

| scale(posts) | LIKE avg(ms) | ES avg(ms) | LIKE/ES |
|---|---|---|---|
| 2000 | 13.166 | 37.303 | **0.35x**（小规模 ES 固定开销占优，反而慢） |
| 10000 | 14.425 | 16.487 | **0.87x**（交叉点） |
| 50000 | 66.726 | 15.823 | **4.22x**（ES 快 4.2 倍） |

LIKE 随 N 近线性上涨（10k→50k 数据 5x，耗时约 4.6x）；ES 近乎恒定（~16ms 与语料规模解耦）。**隔字召回**（N=5000，标题「鸣潮的攻略详解」搜「鸣潮攻略」）：LIKE=false（子串不连续漏召回），ES ik=true 且排首位。

### CDC 端到端延迟实测（全栈 compose 真链路，轮询 500ms）

| 阶段 | 延迟 |
|---|---|
| 发帖→可搜（索引） | **1853 ms** |
| 改帖→搜到新内容（更新） | **569 ms** |
| 逻辑删→搜索消失（删除同步） | **1087 ms** |

### code-review 结论（Standards + Spec 双轴）

- **Standards 轴**：**0 硬违规**，命名与 CONTEXT.md 一致，5 高风险点全过（前端高亮 XSS 安全、503 catch 作用域正确、CDC 幂等、partitionsNum 合理）。2 坏味道判断题（前端映射/loading 态重复）留可选重构。
- **Spec 轴**：3 缺失 + 2 蔓延 + 3 偏差。处置：
  - **(a)1 S2「恢复可搜」未实现 + 工单虚报** → **已修复**：补 `逻辑删后恢复PUBLISHED重新可搜` 测试，工单 5/5→7/7。
  - **(c)1 降级语义过宽（index_not_found→503 误导）** → **已修复**：`SearchIndexBootstrap` 启动软失败引导，活全栈实证 search 返 200 空非 503。
  - (a)2 docs/learning/14 回填 → se-08 本职，**已完成**；(a)3 D8 离线包半做、(b)1 favoriteCount 蔓延、(b)2 excerpt 高亮、(c)2 reindex /admin/、(c)3 reindexAll 全量入内存 → **ACCEPT**（良性/已自注释 demo 规模）。

### ⚠️ vibe learning — canal→RocketMQ producer NPE（CDC 静默不通的致命踩坑）

全栈起好后 `/search` 恒 503、ES 无索引、MQ 无 topic、消费者收不到消息——CDC **从未打通**，但所有单测/门控集成测试全绿（单测直接喂 FlatMessage 绕过真实 producer，测不出）。定位须进容器读 `/home/admin/canal-server/logs/kuros/kuros.log`（不落 stdout）——刷了 **1503 次** `CanalRocketMQProducer.send:242 NPE`。根因：`flatMessage=true`+`partitionHash` 非空命中「分区合并」分支，`partitionNum` 装箱 Integer 三级回退全 null（未配 `canal.mq.partitionsNum`）→ 拆箱 NPE。修复：`compose.yml` 补 `canal.mq.partitionsNum: "4"`（对齐 broker `defaultTopicQueueNums=4`）→ 重建后 NPE=0、CDC 打通。**教训**：真实中间件链路的 bug 单测测不出，S3 端到端冒烟是不可替代的一层。

### 已知限制

① DB↔ES 最终一致，秒级延迟窗口内新帖搜不到（社区可接受）；② CDC 链路挂掉期间索引停更，恢复后从 binlog position 续传；③ Canal 长时间宕机且 binlog 过期的变更需全量重建兜底；④ authorName 冗余进索引，改名需 CDC 捕获 user 库或 TTL 兜底（低频，留下一片）；⑤ 热度排序用 CDC 计数快照非实时值；⑥ `reindexAll` 全量入内存，生产海量需分页 bulk。

### 关联

- Spec：`docs/specs/fulltext-search-cdc.md` · ADR：`docs/adr/0007-fulltext-search-cdc.md` · 复盘：`docs/learning/14-fulltext-search-cdc.md`
- 与 #11（写）+#12（Feed）+#13（读）合成「高并发内容社区」完整读写检索闭环。
- PR：#76（`codex/slice-14-search-cdc` → `main`）。
