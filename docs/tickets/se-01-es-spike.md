# se-01: Spring Data Elasticsearch on Boot 4.1.1 spike（去风险 go/no-go）

**What to build:** 一次性验证切片 #14 的最高风险技术假设——在 Spring Boot 4.1.1 下，Spring Data Elasticsearch 6.1.x 能否正常 index + search 一条中文文档且 ik 分词生效（"鸣潮攻略"命中"鸣潮的攻略"）。跑通后续工单放心铺开；踩坑则据结论回退原生 ES Java Client 手动装配。这是 tracer-bullet 第一发，gate 全链路。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 验证①：Spring Data ES 自动装配的 JsonpMapper（Boot 4 下 Jackson 2/3 共存）能正常序列化 index + search 一条中文文档
- [ ] 验证②：ik 分词生效——Testcontainers 起自建 ik 镜像的 ES 9.4.5，`ik_max_word` 建索引 / `ik_smart` 搜索，"鸣潮攻略"命中"鸣潮的攻略详解"
- [ ] 验证③：国内拉 `docker.elastic.co` ES 镜像 + analysis-ik 9.4.5 插件通畅（不通则离线包兜底 + 可配镜像源）
- [ ] 验证④：RocketMQ 消费端 FlatMessage JSON 反序列化结构 + String→LocalDateTime/long 类型转换可行
- [ ] 验证⑤：Testcontainers ES + 自建 ik 镜像可用 + CI runner 内存扛得住 ES 容器（堆收敛后）
- [ ] 产出 go/no-go 决策记录（走 Spring Data ES 还是回退原生 client），结论写回本工单/spec
- [ ] spike 测试代码明确标注：保留为 se-07 集成测试种子，或一次性验证后清理
- [ ] 编译级快验通过（`mvnw test-compile`）
