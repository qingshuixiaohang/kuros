# Ticket: backend 侧 OpenFeign 集成与组合视图改造（Phase B-4）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：split-07
**阻塞**：split-09

## 范围

1. Feign 客户端：`@FeignClient` 指向 `kuros-user`（`lb://` 服务发现）；超时 connect 1s / read 2s（`spring.cloud.openfeign.client.config.default.*`）；`@EnableFeignClients` 精确列举
2. 内容域改造：替换 6 处 `CommunityUserRepository` 调用为 Feign 批量查询（`authorsById` 模式保留，实现底层换源）；`ProfileService` 组合视图改造（公开资料/个人中心的用户与关注数据经 Feign 取，内容统计本地查）
3. 降级策略：Feign 失败时作者信息占位（昵称"用户"、空头像），帖子列表/详情照常返回；资料页返回 503
4. 测试（TDD 先 RED）：
   - Feign 桩测试：JDK HttpServer 模拟 kuros-user + url 属性直连（覆盖作者组装/批量/降级，秒级）
   - Nacos 真实链路测试：Testcontainers nacos-server + 桩注册为 `kuros-user`，验证 `lb://` + Feign 端到端
5. 缓存注释：`publicProfile` 的 60s TTL 兜底处补注"生产需事件驱动失效（RocketMQ 切片）"

## 验收

- [ ] 帖子列表/详情/评论的作者信息经 Feign 取得，字段与拆分前一致
- [ ] kuros-user 不可用时列表降级占位不 500；资料页 503
- [ ] Feign 桩测试 + Nacos 链路测试全绿；backend 全量测试绿

## 备注

- 依赖单向：内容域 → 用户域；组合视图不进用户域（拒绝双向依赖）
