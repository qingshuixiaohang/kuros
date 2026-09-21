# 0003. 服务拆分：拆出第一个业务微服务 kuros-user

日期：2026-09-21

状态：已接受

## 背景与问题

切片 #9 完成后，系统形态为"Gateway 正门 + 单体 backend"：网关的 `lb://kuros-backend` 只有单一上游，服务发现与统一入口的架构价值没有兑现。用户域（认证、验证码、会话、RBAC、公开资料、关注）与内容域（帖子、评论、互动、举报、媒体）耦合在同一个进程、同一数据库：

1. **边界模糊**：内容服务直接引用 `CommunityUserRepository`（6 处），用户资料变更与内容功能共享发布节奏。
2. **无法独立扩容**：认证是高流量端点（验证码/登录），与内容读写互相争抢同一进程资源。
3. **架构叙事不完整**：简历与面试需要"从单体拆出第一个微服务"的完整故事：边界判定、数据拆分、服务间通信、容错降级、验证闭环。

参照犬小哈《Spring Cloud Alibaba 小哈书》的服务拆分章节，本切片完成第一次真实拆分。

## 决策

分两阶段拆出 **kuros-user**（用户域微服务）：

1. **Phase A 按模块重排**：backend 内部从按技术层分包（api/config/domain/web/service）重排为按业务模块分包（user/post/comment/interaction/report/media/shared）。纯移动、零行为变化、全量测试绿验收。重排让边界显式化，拆分时整目录端走；两阶段可二分回退。

2. **Phase B 拆出 kuros-user**：认证 + 验证码 + RBAC（四表 + StpInterface）+ 用户资料实体 + 关注关系；独立工程、独立部署（容器 8080 / 宿主 8091）、独立数据库 `kuros_user`（同 MySQL 实例）。backend 的 V10 迁移 DROP 迁走的表；死代码 `UserSession` 一并删除。

3. **组合视图留 backend，依赖单向**：`/users/{id}`、`/users/{id}/posts`、`/users/me/profile` 是跨域组合视图（内容统计 + 用户/关注数据），留在 backend 组装；backend 经 OpenFeign（`lb://kuros-user`）取用户数据。依赖方向固定为**内容域 → 用户域**，不制造双向依赖。

4. **跨库外键消失**：`kuros_user` 与 `kuros` 两个库之间没有外键约束，一致性降级为服务契约：seed 用相同 ID 对齐、Feign 失败降级占位、生产事件驱动同步（留 RocketMQ 切片）。

5. **会话零迁移**：两个服务共享同一 Redis + 同名 Cookie（`KUROS_SESSION`）+ 相同 SaToken 配置，登录态天然跨服务——切片 #1 分布式会话决策的远期回报。

6. **有纪律的复制**：不建 common 模块；SaToken 配置、CSRF、actuator 等按需复制到 kuros-user（rule of three：第三处重复时再抽公共模块）。

## 备选方案

- **按技术层直接抽取（跳过重排）**：拆分时要跨 6 个包"挑拣"相关类，边界不可见、出错难定位；重排是一次零风险的显式化投资。
- **组合视图挪到 kuros-user（双向依赖）**：资料页数据来自两个域，若归用户域则用户域需反向 Feign 调内容域——A↔B 循环依赖，启动顺序敏感、故障级联，微服务经典反模式。
- **立即建 Maven 多模块 common**：本切片复杂度翻倍（改造 backend 构建结构 + 新工程继承），而重复点只有 2 处（未达 rule of three），过早抽象。
- **先拆帖子/评论域**：内容域实体被互动/举报/媒体深度引用，牵出的是整个内容域；用户域边界最清晰且被全站依赖，先拆收益/风险比最优。
- **event-driven 数据同步（RocketMQ）**：正确但超范围，留给后续切片；当前用 TTL 兜底 + 降级占位即可接受。

## 影响

- **前端**：零改动（API 路径、Cookie、CSRF 契约不变；全部流量经网关）。
- **API 契约**：外部无变化；kuros-user 新增内部接口（批量用户查询、关注状态/计数），不经网关、暂不鉴权（生产需 mTLS/内部 token，已注释）。
- **部署**：compose 新增 kuros-user（8 个服务）；mysql-init 新增 `kuros_user` 建库脚本；CI 新增 User service tests job（第 5 个）。
- **运维面**：kuros-user 自带 health/prometheus/Nacos 配置中心接入，与 backend 对齐。
- **已知限制**：跨库无 FK（约束降级为契约）；publicProfile 缓存 60s TTL 兜底；直连 8090 调试时 user 端点 404（调试走网关）。

## 对应小哈书章节

- 服务拆分章节：按业务域拆库拆服务、OpenFeign 声明式调用、服务间容错。

## 简历产出

> 将单体后端按业务域拆分为用户服务与内容服务两个独立部署单元：先做模块化重排（零行为变化）再物理拆分，通过 OpenFeign + Nacos 服务发现完成服务间调用（超时 1s/2s + 降级占位防雪崩）；跨库一致性从外键约束降级为"约定 ID + 服务契约"，登录态经共享 Redis 会话零迁移穿透双服务；网关按 API 前缀分流，冒烟脚本以"经网关登录 → 跨服务发帖 → 作者信息回填"一条链路完成端到端验证。
