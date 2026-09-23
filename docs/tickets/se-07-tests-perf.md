# se-07: 测试 + perf 收口（S1~S4）

**What to build:** 用可复现的测试与真实性能数据证明切片 #14 解决了难点——搜索功能正确性、CDC 端到端同步、LIKE vs ES 的性能对比。禁止编造 TPS/RT 数字，只用实测数据回填 `docs/learning/14`。

**Blocked by:** se-03, se-04, se-05, se-06

**Status:** ready-for-agent

- [ ] S1 搜索 HTTP 契约集成测试（Testcontainers ES 自建 ik 镜像 + H2）：分词命中/多字段 boost/高亮/过滤/三排序/search_after 深翻/CursorPageResult 契约/ES 降级
- [ ] S2 CDC 索引写入集成测试：seed→index→断言 ES 文档、全量重建、DELETED 过滤、恢复可搜、幂等
- [ ] S3 compose 端到端冒烟（扩展 `scripts/compose-smoke.mjs` 或新增 `search-cdc-smoke.mjs`）：发帖→轮询 `/search` 命中→记录端到端延迟→改帖/删帖验证同步；进 CI deployment job（ES 堆收敛 + Kibana 不启），runner OOM 则降级本地脚本（据 se-01 spike 结论）
- [ ] S4 门控 perf（`@EnabledIfSystemProperty(perf=true)`）：`LIKE '%x%'` vs ES 查询在 N 条帖子下耗时对比 + CDC 端到端延迟实测，`[PERF]` 前缀输出真实数据；默认不进 CI
- [ ] 主套件兼容：不破坏现有 83 测试；ES 集成测试用独立 H2 库名 + 每类独立 JVM（`reuseForks=false`）
- [ ] 编译级快验通过；耗时测试（全量 `mvn test` / Testcontainers / compose 冒烟 / perf）交用户或 CI 执行
