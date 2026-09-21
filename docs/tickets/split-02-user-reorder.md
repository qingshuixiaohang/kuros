# Ticket: 重排 user 模块（Phase A-2）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：split-01
**阻塞**：split-03

## 范围

1. 用户域类归入 `user` 模块包：`AuthService`/`ProfileService`/`UserFollowService`、`CommunityUser`/`SysRole`/`SysPermission`/`UserRole`/`UserFollow` 实体、RBAC 与用户仓储、`AuthController`/`ProfileController`/`UserFollowController`、验证码三件套（`SmsSender`/`DevSmsSender`/`RedisVerificationCodeService`）、`StpInterfaceImpl`、用户相关 DTO
2. 更新内容模块对用户域的所有引用（import 路径），纯移动零行为变化
3. 验收：全量后端测试绿

## 验收

- [ ] `user` 模块包建立，用户域类归位
- [ ] 全量测试绿，无行为变化

## 备注

- `ProfileService` 的公开资料/个人中心是组合视图（含内容统计），本阶段仅移动包，不改变其内部对内容仓储的调用——Phase B 再改造为 Feign
