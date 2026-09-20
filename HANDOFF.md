# 项目交接文档（2026-09-20）

## 当前状态

**切片 #1 已完成**：分布式会话与鉴权重构（SaToken + Redis + RBAC）
- PR #59 已创建，等待合并
- Issue #58 已关闭
- 所有测试通过：34 后端 + 68 Playwright e2e
- 会话重启持久化验证通过

**下一步**：切片 #2 — MinIO 对象存储 + 策略模式

---

## 项目架构概览

### 技术栈
- **后端**：Spring Boot 4.1.1 + Java 21 + Maven
- **前端**：Next.js 15 + TypeScript
- **数据库**：MySQL 8.0 + Flyway 迁移
- **缓存**：Redis 7（分布式会话 + 权限缓存）
- **认证**：SaToken 1.39.0（替代 Spring Security）
- **测试**：JUnit 5 + Testcontainers 2.0.5 + Playwright

### 核心模块

#### 1. 认证与授权（已完成）
- **SaToken 配置**：`SaTokenConfigure.java`
  - order 0：鉴权拦截器（`SaInterceptor`）
  - order 1：CSRF 拦截器（`CsrfInterceptor`）
  - 公开路由白名单：方法级粒度（GET + 路径）
  - OPTIONS 预检显式放行

- **RBAC 权限模型**：
  - 四张表：`sys_role` / `sys_permission` / `sys_user_role` / `sys_role_permission`
  - Flyway V9 迁移
  - `StpInterfaceImpl`：优先从 Redis 读取角色/权限，回源 DB 兜底
  - 登录时同步：`TransactionSynchronization.afterCommit()` 延迟到事务提交后

- **验证码服务**：
  - `RedisVerificationCodeService`：Redis 存储 + TTL + 60s 频率限制
  - dev 模式跳过频率限制（固定码 123456）
  - 异步短信：`SmsSender` 策略接口 + `AsyncConfig` 自定义线程池

- **CSRF 防护**：
  - `CsrfInterceptor`：双重提交 Cookie（XSRF-TOKEN Cookie + X-XSRF-TOKEN Header）
  - 排除 `/api/v1/auth/**`（匿名请求无 CSRF Token）

#### 2. 对象存储（待实现 — 切片 #2）
- **目标**：引入 MinIO 替代本地文件存储
- **设计模式**：策略模式（`StorageStrategy` 接口）
  - `LocalStorageStrategy`：本地磁盘（开发环境）
  - `MinIOStorageStrategy`：MinIO 对象存储（生产环境）
- **关键文件**：
  - `StorageStrategy.java`（接口）
  - `LocalStorageStrategy.java`（现有逻辑提取）
  - `MinIOStorageStrategy.java`（新增）
  - `StorageConfig.java`（配置类，根据 profile 选择策略）
- **基础设施**：
  - `compose.yml` 新增 MinIO 服务
  - `pom.xml` 新增 `minio` SDK 依赖
  - `.env` 新增 MinIO 配置（endpoint、accessKey、secretKey、bucket）

---

## 开发规范与约定

### 1. 拦截器顺序（重要）
```java
// SaTokenConfigure.java
registry.addInterceptor(sAuthInterceptor).order(0);  // 鉴权在前
registry.addInterceptor(csrfInterceptor).order(1);   // CSRF 在后
```
**原因**：旧版 Spring Security 的 CSRF 忽略条件是"匿名请求"，游客写操作应先吃 401（鉴权失败），而非 403（CSRF 失败）。

### 2. 公开路由白名单（方法级粒度）
```java
// 正确：GET 方法 + 路径双重匹配
SaRouter.match(SaHttpMethod.GET).match("/api/v1/posts/**").stop();

// 错误：仅路径匹配（会导致 POST 也公开）
SaRouter.match("/api/v1/posts/**").stop();
```
**原因**：旧版 Spring Security 的 `permitAll()` 是精确匹配 HTTP 方法的。

### 3. RBAC 查询（nativeQuery）
```java
// SysRoleRepository.java
@Query(value = """
    SELECT r.role_code FROM sys_role r
    JOIN sys_user_role ur ON ur.role_id = r.id
    WHERE ur.user_id = :userId
    """, nativeQuery = true)
List<String> findRoleCodesByUserId(@Param("userId") String userId);
```
**原因**：JPQL 只能 join 已映射的实体类，不能 join 原生表名（未映射实体）。关联表用 nativeQuery。

### 4. 事务边界（Redis 写入）
```java
@Transactional
public void login() {
    // DB 操作
    userRepository.save(user);
    
    // Redis 写入延迟到事务提交后
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                syncPermissionsToRedis(userId);
            }
        });
}
```
**原因**：Redis 是非事务资源，DB 回滚时 Redis 无法回滚，会产生孤儿数据。

### 5. CSRF 排除路径
```java
// SaTokenConfigure.java
registry.addInterceptor(csrfInterceptor)
    .excludePathPatterns("/api/v1/auth/**");
```
**原因**：前端 `api.ts` 对 auth 路径不发 X-XSRF-TOKEN Header，若不排除会导致登录 403。

### 6. Flyway 迁移（role_code 子查询）
```sql
-- V9__rbac.sql
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM users u, sys_role r
WHERE r.role_code = 'USER' AND u.phone = '13800000001';
```
**原因**：不依赖自增 ID 顺序（不同环境 ID 可能不同），用 `role_code` 子查询保证跨环境一致性。

---

## 测试策略

### 后端测试（JUnit 5 + Testcontainers）
- **测试类**：`KurosBackendApplicationTests.java`
- **Redis 容器**：`@Container static GenericContainer<?> redis`
- **动态属性**：`@DynamicPropertySource` 注入随机端口
- **CSRF 注入**：手动设置 Cookie + Header（旧版用 `csrf()` post-processor）
- **覆盖**：登录/登出、角色校验、权限校验、CSRF、公开路由、事务边界

### 前端测试（Playwright）
- **配置**：`playwright.config.ts`
- **复用现有服务**：`reuseExistingServer: true`（对着 compose 容器跑）
- **覆盖**：68 个 e2e 测试（登录、发帖、评论、上传、UI 回归）

### 全栈验证
- **冒烟测试**：`scripts/compose-smoke.mjs`
  - 登录 → 发帖 → 评论 → 传图 → 数据持久化
- **会话持久化**：`scripts/session-persistence-check.mjs`
  - login 模式：登录 + 保存 Cookie + 立即访问
  - check 模式：重启后端 + 用旧 Cookie 访问（验证 Redis 会话）

---

## 关键文件索引

### 认证相关
- `kuros-backend/src/main/java/com/kuros/kurosbackend/config/SaTokenConfigure.java`
- `kuros-backend/src/main/java/com/kuros/kurosbackend/config/CsrfInterceptor.java`
- `kuros-backend/src/main/java/com/kuros/kurosbackend/auth/StpInterfaceImpl.java`
- `kuros-backend/src/main/java/com/kuros/kurosbackend/auth/RedisVerificationCodeService.java`
- `kuros-backend/src/main/java/com/kuros/kurosbackend/service/AuthService.java`

### RBAC 相关
- `kuros-backend/src/main/resources/db/migration/V9__rbac.sql`
- `kuros-backend/src/main/java/com/kuros/kurosbackend/domain/SysRole.java`
- `kuros-backend/src/main/java/com/kuros/kurosbackend/domain/SysPermission.java`
- `kuros-backend/src/main/java/com/kuros/kurosbackend/repository/SysRoleRepository.java`
- `kuros-backend/src/main/java/com/kuros/kurosbackend/repository/SysPermissionRepository.java`

### 基础设施
- `compose.yml`（MySQL + Redis + backend + frontend）
- `kuros-backend/Dockerfile`（BuildKit 缓存挂载）
- `kuros-backend/pom.xml`（依赖管理）
- `.env.example`（环境变量模板）

### 测试与脚本
- `kuros-backend/src/test/java/com/kuros/kurosbackend/KurosBackendApplicationTests.java`
- `scripts/compose-smoke.mjs`
- `scripts/session-persistence-check.mjs`

### 文档
- `docs/adr/0002-distributed-session-satoken-redis.md`（架构决策）
- `docs/specs/distributed-session-auth.md`（切片规格）
- `CONTEXT.md`（项目上下文）

---

## 微服务演进路线（10 步）

1. ✅ **切片 #1**：分布式会话与鉴权重构（SaToken + Redis + RBAC）— 已完成
2. ⏳ **切片 #2**：MinIO 对象存储 + 策略模式 — 下一步
3. ⏸ **切片 #3**：Nacos 服务注册与发现
4. ⏸ **切片 #4**：Gateway 统一网关 + 鉴权前置
5. ⏸ **切片 #5**：RocketMQ 异步消息（帖子发布 → 通知/搜索索引）
6. ⏸ **切片 #6**：Elasticsearch 全文搜索
7. ⏸ **切片 #7**：Canal + Redis 缓存同步
8. ⏸ **切片 #8**：XXL-JOB 定时任务
9. ⏸ **切片 #9**：Sentinel 限流降级
10. ⏸ **切片 #10**：Zookeeper 分布式锁（帖子点赞防并发）

---

## 分工规则

### 用户负责
- 中间件下载/安装/启动（Redis、MinIO、Nacos 等）
- Maven/npm 新依赖拉取（`mvn install`、`npm install`）
- 容器操作（`docker compose up`、`docker compose restart`）

### AI 负责
- 业务代码、配置文件、SQL、测试、docker-compose 片段
- 只读验证命令（`mvn test`、`npm run lint`、`playwright test`）
- 代码审查、提交、PR、Issue 回写

### 学习模式
- **vibe learning**：用户在观看编码和运行环境的过程中吸收经验
- 代码注释要解释"为什么这么写"而非"是什么"
- 关键设计决策要讲清楚难点与取舍

---

## 常见陷阱（已踩坑）

### 1. Spring Boot 4 + Jackson 2 依赖
- **问题**：Boot 4 默认 Jackson 3（`tools.jackson.*`），`sa-token-redis-jackson` 需要 Jackson 2（`com.fasterxml.jackson.*`）
- **解法**：显式补齐 `jackson-databind` + `jackson-datatype-jsr310` 2.19.0

### 2. Testcontainers 2.x + Docker Engine 29
- **问题**：Testcontainers 1.x 的 docker-java 客户端用 API 1.32，Engine 29 最低要求 1.44
- **解法**：升级到 Testcontainers 2.0.5，`GenericContainer` 包名改回 `org.testcontainers.containers`

### 3. JPQL join 未映射实体
- **问题**：JPQL 只能 join 已映射的实体类，不能 join 原生表名
- **解法**：关联表用 `nativeQuery = true`

### 4. CSRF 拦截器顺序
- **问题**：CSRF 在前、鉴权在后 → 游客写操作返回 403（应为 401）
- **解法**：鉴权 order 0、CSRF order 1

### 5. 公开路由白名单方法粒度
- **问题**：仅按路径豁免 → POST 也公开
- **解法**：`SaRouter.match(SaHttpMethod.GET).match(路径).stop()`

### 6. Docker 构建 Maven 静默下载
- **问题**：BuildKit 默认折叠日志，看起来像"卡死"
- **解法**：`docker compose build --progress=plain`，Dockerfile 添加 `--mount=type=cache,target=/root/.m2`

---

## 下一步行动

### 切片 #2：MinIO 对象存储 + 策略模式

#### 目标
- 引入 MinIO 替代本地文件存储
- 设计 `StorageStrategy` 接口，支持本地/MinIO 切换
- compose 新增 MinIO 服务
- 前端零改动（保持 `/media/**` 访问路径）

#### 关键决策
- **策略模式**：`StorageStrategy` 接口 + `LocalStorageStrategy` / `MinIOStorageStrategy` 实现
- **配置驱动**：`@Profile` 或 `@ConditionalOnProperty` 选择策略
- **向后兼容**：保留 `/media/**` 静态资源映射，MinIO 通过预签名 URL 或直接代理访问

#### 验证矩阵
- [ ] 单元测试：策略切换、上传/下载/删除
- [ ] 集成测试：MinIO 容器（Testcontainers）
- [ ] 冒烟测试：compose 全栈上传 → 访问 URL
- [ ] 前端回归：Playwright e2e 不挂

#### 参考
- 犬小哈《Spring Cloud Alibaba 小哈书》第七章（MinIO 对象存储）
- MinIO 官方文档：https://min.io/docs/minio/container/index.html

---

## 联系方式

如有问题，参考：
- `CONTEXT.md`：项目上下文
- `docs/adr/`：架构决策记录
- `docs/specs/`：切片规格
- GitHub Issues：https://github.com/qingshuixiaohang/kuros/issues
