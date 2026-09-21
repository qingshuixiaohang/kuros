# Ticket: kuros-user 工程骨架 + 独立库 + 注册发现（Phase B-1）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：split-04
**阻塞**：split-06

## 范围

1. 新建平级工程 `kuros-user/`：独立 pom（Boot 4.1.1 + SC 2025.1.1 + SCA 2025.1.0.0 + nacos discovery/config + actuator + prometheus + SaToken 全家桶）、Maven Wrapper、Dockerfile、启动类
2. 配置：独立数据源指向 `kuros_user` 库；共享 Redis；`spring.config.import=optional:nacos:kuros-user.properties`；actuator 暴露 health/prometheus
3. Flyway：V1 用户域建表（community_user、sys_role、sys_permission、sys_user_role、sys_role_permission、user_follow）+ V2 seed（测试用户与 RBAC 数据，**ID/手机号与 backend 种子完全相同**）
4. `docker/mysql-init` 增加建 `kuros_user` 库脚本；compose 新增 `kuros-user` 服务（宿主 8091 → 容器 8080，depends_on mysql/redis/nacos healthy）
5. CI 新增 `User service tests` job（第 5 个）
6. 验收：服务启动注册为 `kuros-user`；`/actuator/health`、`/actuator/prometheus` 200；CI 五 job 全绿

## 验收

- [ ] kuros-user 注册 Nacos healthy 实例
- [ ] health/prometheus 端点可用
- [ ] Flyway 在全新库执行成功，seed 与应用字段对齐
- [ ] compose 配置校验通过；CI 第 5 job 绿

## 备注

- 本 ticket 只交付"能启动、能注册、能迁移"的骨架，不带业务端点（垂直切片从 split-06 开始）
- `NacosContainers` 固定端口模式复制到新工程（rule of three 前的纪律性复制）
