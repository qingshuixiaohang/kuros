# 切片 #14 Ticket 拆解（全文检索 + CDC 增量索引：ES ik 分词 + Canal 订阅 binlog）

**父 Issue**：[#75](https://github.com/qingshuixiaohang/kuros/issues/75) · **Spec**：`docs/specs/fulltext-search-cdc.md` · **ADR**：`docs/adr/0007-fulltext-search-cdc.md`

| # | Ticket | 阻塞于 | 交付 |
|---|--------|--------|------|
| 1 | [se-01-es-spike](./se-01-es-spike.md) | 无 | Spring Data ES on Boot 4.1.1 spike：ik 分词 index+search 中文、JsonpMapper、Testcontainers 自建 ik 镜像、FlatMessage 反序列化、CI 内存 → go/no-go 决策 |
| 2 | [se-02-deploy-infra](./se-02-deploy-infra.md) | se-01 | compose ES 9.4.5(+ik Dockerfile) + canal-server 1.1.8 + Kibana(profile) + MySQL binlog 参数 + canal 复制账号 + backend ES 软依赖配置 |
| 3 | [se-03-index-service](./se-03-index-service.md) | se-02 | PostSearchDoc mapping(ik) + PostIndexService 回源组装(DB+Feign)/`_id` 幂等 upsert + 删除语义 + index alias + 全量重建端点（S2 seam） |
| 4 | [se-04-search-api](./se-04-search-api.md) | se-03 | `GET /api/v1/search`：multi_match+boost+ik_smart+高亮+过滤+三排序+search_after → CursorPageResult；ES 降级（S1 seam） |
| 5 | [se-05-cdc-pipeline](./se-05-cdc-pipeline.md) | se-02, se-03 | Canal(posts+post_tags, partitionHash 保序) → RocketMQ(FlatMessage) → backend 消费者(orderly+重试+死信) → 触发 PostIndexService |
| 6 | [se-06-frontend-search](./se-06-frontend-search.md) | se-04 | 前端搜索框 keyword 非空切 `/api/v1/search` + `<mark>` 高亮 + 复用 #13 无限滚动（最小载体） |
| 7 | [se-07-tests-perf](./se-07-tests-perf.md) | se-03, se-04, se-05, se-06 | S1 搜索契约 + S2 索引服务集成测试 + S3 compose 端到端冒烟 + S4 门控 perf（LIKE vs ES + CDC 延迟，实测数据） |
| 8 | [se-08-docs](./se-08-docs.md) | se-07 | docs/learning/14 七段式复盘 + Issue #75 回写 + code-review + PR |

## 依赖图（DAG）

```
se-01(spike) ─→ se-02(基建)
                   └─→ se-03(索引服务) ─┬─→ se-04(搜索API) ─→ se-06(前端)
                                        └─→ se-05(CDC管道)
汇合: se-03 se-04 se-05 se-06 ─→ se-07(测试+perf) ─→ se-08(docs+PR)
```

- **frontier（可立即起跑）**：仅 se-01（spike 是最高风险项，单独 gate 全链路）。
- se-04（搜索轨）与 se-05（CDC 轨）在 se-03 就位后可**并行**——搜索靠手动 index 喂数据即可验证，不依赖 CDC。
- se-05 阻塞于 se-02（canal/mq/binlog 基建）+ se-03（PostIndexService）。
- se-07 阻塞于全部实现 ticket（跨切面集成测试 + 端到端冒烟 + 性能验证）；se-08 收口。

## 执行说明

- 每个 ticket 是一个可独立验收的垂直切片，按 frontier 顺序执行；搜索轨（se-04→se-06）与 CDC 轨（se-05）在 se-03 后可并行。
- **TDD**：每个 ticket 先写失败测试再实现最小行为；se-07 做跨切面集成测试 + 性能验证收口。
- **spike 优先**：se-01 是 tracer-bullet 第一发，跑通 Spring Data ES + ik 才铺开；踩坑则据 go/no-go 结论回退原生 ES Java Client。
- **耗时测试纪律**：全量 `mvn test`、Testcontainers 集成、compose 冒烟、性能压测**交用户/CI**；AI 只做编译级快验（`test-compile` / `compose config` / `node --check`）。
- **收益表述纪律**：只用可验证结构性指标 + 实测数据，不编造 TPS/RT 并发数字（CONTEXT.md 方向纠偏）。
- **关键正确性约束**（se-03 起全程遵守）：CDC 消费端**回源组装**（不直接用 FlatMessage data 拼文档），binlog 单表行缺 tags(join)/authorName(跨库)；ES `_id=postId` 保幂等；逻辑删靠 status 过滤保可逆。
