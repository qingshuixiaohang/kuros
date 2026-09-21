# 规格：分布式会话与鉴权重构（SaToken + Redis + RBAC）

日期：2026-09-20
ADR：[0002-distributed-session-satoken-redis](../adr/0002-distributed-session-satoken-redis.md)
对应小哈书：第五章（SaToken + Redis 登录、RBAC、验证码异步发送）

## 范围

将当前 Spring Security + MySQL Session + ConcurrentHashMap 验证码的认证体系，替换为 SaToken + Redis + RBAC 四张表 + 异步验证码。保持 Cookie 模式、保持 API 契约不变、保持单体部署。

## 非目标

- 不引入 Nacos、Gateway、Sentinel、RocketMQ、MinIO、Elasticsearch
- 不拆分微服务
- 不接入真实短信 SDK（只预留 `SmsSender` 接口）
- 不改变前端 UI 或交互流程
- 不删除 `user_sessions` 表（保留但不再写入）
- 不改变 `/api/v1/auth/*` 的请求/响应格式

## 后端变更

### 1. 依赖变更（pom.xml）

移除：
- `spring-boot-starter-security`
- `spring-security-test`（test scope）

新增：
- `sa-token-spring-boot3-starter`（SaToken 核心）
- `sa-token-redis-jackson`（SaToken Redis 集成，Jackson 序列化）
- `spring-boot-starter-data-redis`（Spring Data Redis + Lettuce）
- `org.apache.commons:commons-pool2`（Redis 连接池）
- `org.testcontainers:testcontainers`（test scope）
- `com.redis:testcontainers-redis`（test scope，Redis Testcontainer）

### 2. SaToken 配置（application.properties）

```properties
# SaToken 配置
sa-token.token-name=KUROS_SESSION
sa-token.timeout=2592000
sa-token.active-timeout=-1
sa-token.is-concurrent=true
sa-token.is-share=false
sa-token.token-style=uuid
sa-token.is-log=false
sa-token.is-read-cookie=true
sa-token.is-read-header=false
sa-token.cookie-httponly=true
sa-token.cookie-secure=${APP_AUTH_SESSION_COOKIE_SECURE:false}
sa-token.cookie-same-site=Lax

# Redis 配置
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.database=0
spring.data.redis.lettuce.pool.max-active=16
spring.data.redis.lettuce.pool.max-idle=8
spring.data.redis.lettuce.pool.min-idle=2
```

### 3. RBAC 数据库迁移（V9__rbac.sql）

```sql
CREATE TABLE sys_role (
    id INT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    role_name VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_permission (
    id INT AUTO_INCREMENT PRIMARY KEY,
    permission_code VARCHAR(64) NOT NULL UNIQUE,
    permission_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    role_id INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT uk_user_role UNIQUE (user_id, role_id)
);

CREATE TABLE sys_role_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id INT NOT NULL,
    permission_id INT NOT NULL,
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES sys_permission (id),
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
);

-- Seed 角色
INSERT INTO sys_role (role_code, role_name) VALUES ('USER', '普通用户'), ('ADMIN', '管理员');

-- Seed 权限
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('post:create', '发布帖子'),
('post:delete:own', '删除自己的帖子'),
('post:delete:any', '删除任意帖子'),
('comment:create', '发表评论'),
('comment:delete:own', '删除自己的评论'),
('comment:delete:any', '删除任意评论'),
('report:create', '提交举报'),
('report:handle', '处理举报'),
('user:ban', '封禁用户'),
('character:manage', '管理角色图鉴');

-- ADMIN 拥有全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 2, id FROM sys_permission;

-- USER 拥有基础权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 1, id FROM sys_permission WHERE permission_code IN
('post:create', 'post:delete:own', 'comment:create', 'comment:delete:own', 'report:create');

-- 将现有 ADMIN 用户关联到 RBAC 表
INSERT INTO sys_user_role (user_id, role_id)
SELECT id, 2 FROM users WHERE role = 'ADMIN';

-- 将现有 USER 用户关联到 RBAC 表
INSERT INTO sys_user_role (user_id, role_id)
SELECT id, 1 FROM users WHERE role = 'USER';
```

### 4. 核心类变更

| 操作 | 文件 | 说明 |
|---|---|---|
| 删除 | `config/SecurityConfig.java` | Spring Security 过滤器链 |
| 删除 | `web/SessionAuthenticationFilter.java` | 自定义 Session 过滤器 |
| 新增 | `config/SaTokenConfigure.java` | SaToken 拦截器 + 路由规则 + CORS |
| 新增 | `config/CsrfInterceptor.java` | 双重提交 Cookie CSRF 拦截器 |
| 新增 | `config/CorsConfig.java` | WebMvcConfigurer CORS 配置 |
| 新增 | `auth/StpInterfaceImpl.java` | SaToken 权限数据源（从 Redis 读角色/权限） |
| 新增 | `auth/RedisVerificationCodeService.java` | 验证码 Redis 存储 + TTL + 频率限制 |
| 新增 | `auth/SmsSender.java` | 短信发送接口 |
| 新增 | `auth/DevSmsSender.java` | 开发环境实现（日志输出） |
| 新增 | `config/AsyncConfig.java` | 自定义线程池（验证码异步发送） |
| 新增 | `domain/SysRole.java` | 角色实体 |
| 新增 | `domain/SysPermission.java` | 权限实体 |
| 新增 | `repository/SysRoleRepository.java` | 角色 DAO |
| 新增 | `repository/SysPermissionRepository.java` | 权限 DAO |
| 新增 | `repository/SysUserRoleRepository.java` | 用户角色关联 DAO |
| 重构 | `service/AuthService.java` | 登录改用 `StpUtil.login(userId)`，登出改用 `StpUtil.logout()`，currentUser 改用 `StpUtil.getLoginIdAsString()` |
| 重构 | `web/AuthController.java` | 移除手动 Cookie 操作（SaToken 自动管理），保留 `/csrf` 端点 |
| 保留 | `domain/CommunityUser.java` | `role` 字段保留为冗余快捷字段 |
| 保留 | `domain/UserSession.java` | 实体保留但代码不再使用 |

### 5. SaToken 路由拦截规则

```java
// SaTokenConfigure.java 核心逻辑
registry.addInterceptor(new SaInterceptor(handle -> {
    // 注解鉴权优先
    SaRouter.match("/**").notMatch(
        "/api/v1/auth/**",
        "/api/v1/posts/*/comments",
        "/api/v1/posts",
        "/api/v1/posts/*",
        "/api/v1/users/*/profile",
        "/api/v1/users/*/posts",
        "/api/v1/users/*/follow",
        "/api/v1/posts/*/interactions",
        "/api/v1/characters/**",
        "/actuator/health"
    ).check(r -> StpUtil.checkLogin());

    // 管理员路由
    SaRouter.match("/api/v1/admin/**").check(r -> StpUtil.checkRole("ADMIN"));
}))
.addPathPatterns("/**")
.excludePathPatterns("/error");
```

### 6. CSRF 双重提交 Cookie

```java
// CsrfInterceptor.java 核心逻辑
// GET/HEAD/OPTIONS 请求：如果 XSRF-TOKEN cookie 不存在，生成一个
// POST/PUT/DELETE 请求：校验 X-XSRF-TOKEN header == XSRF-TOKEN cookie
// 不匹配则返回 403 CSRF_TOKEN_MISMATCH
```

前端现有逻辑（`api.ts` 的 `request()` 和 `refreshCsrfCookie()`）完全不用改。

### 7. 验证码服务重构

```java
// RedisVerificationCodeService.java
// issue(phone):
//   1. 检查 Redis key "sms:code:{phone}" 是否存在 → 存在则抛频率限制异常
//   2. 生成 6 位随机码
//   3. 写入 Redis: key="sms:code:{phone}", value=code, TTL=300s
//   4. 写入频率限制: key="sms:limit:{phone}", value="1", TTL=60s
//   5. 异步调用 SmsSender.send(phone, code)
//   6. 返回 code（dev 环境暴露）
//
// verify(phone, code):
//   1. 从 Redis 读 "sms:code:{phone}"
//   2. 比对，成功则删除 key
//   3. 返回 boolean
```

### 8. 登录流程变更

```
之前：verify code → find/create user → random token → hash → save to MySQL → set cookie
之后：verify code (Redis) → find/create user → StpUtil.login(userId) → SaToken 自动写 Redis + 设 Cookie
     → 从 RBAC 表查角色/权限 → 同步到 Redis → 同步 users.role 冗余字段
```

### 9. 认证请求性能变化

| 操作 | 之前 | 之后 |
|---|---|---|
| 每次认证请求 | 2 次 MySQL 查询（session + user） | 1 次 Redis 查询（SaToken 内部） |
| 登录 | 1 次 MySQL INSERT（session） | 1 次 Redis SET（token） + 1 次 Redis SET（权限） |
| 登出 | 1 次 MySQL DELETE | 1 次 Redis DEL |
| 验证码发送 | ConcurrentHashMap PUT | 1 次 Redis SET + TTL |

## 前端变更

**几乎为零**。具体：
- Cookie 名保持 `KUROS_SESSION`（SaToken 配置 `token-name=KUROS_SESSION`）
- `/api/v1/auth/csrf` 端点保留，响应格式不变
- `api.ts` 的 `credentials: "include"` 和 `X-XSRF-TOKEN` Header 逻辑不变
- 登录/登出/me 的请求和响应格式不变
- 唯一可能的变化：如果 SaToken 的 Cookie 属性（Path/Domain/SameSite）与之前不同，需要验证前端行为一致

## Docker Compose 变更

`compose.yml` 新增 Redis 服务：

```yaml
  redis:
    image: redis:7-alpine
    restart: unless-stopped
    ports:
      - "${REDIS_PORT:-6379}:6379"
    volumes:
      - kuros_redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 10

volumes:
  kuros_redis_data:
```

`backend` 服务新增环境变量：
```yaml
      REDIS_HOST: redis
      REDIS_PORT: 6379
```

`backend` 的 `depends_on` 新增：
```yaml
      redis:
        condition: service_healthy
```

## 测试策略

### 测试环境 Redis
- 使用 Testcontainers 启动真实 Redis 容器
- 测试类添加 `@Testcontainers` + `@Container` 注解
- 通过 `@DynamicPropertySource` 注入 Redis 连接信息

### 现有测试迁移
- `login()` 辅助方法：改为调用 `/api/v1/auth/code` + `/api/v1/auth/login`，从响应中获取 SaToken 设置的 Cookie
- CSRF：保留 `csrf()` post-processor 或改为手动设置 `X-XSRF-TOKEN` Header + Cookie
- 业务断言：完全不变
- `@ActiveProfiles("test")`：保留，test profile 配置 Testcontainers Redis

### 新增测试
- RBAC 权限测试：ADMIN 可以访问 `/api/v1/admin/**`，USER 不可以
- Redis 会话持久化测试：登录后 Token 存在于 Redis
- 验证码频率限制测试：60s 内重复发送返回错误
- 验证码 Redis TTL 测试：过期后验证失败

## 验收标准

- [ ] Spring Security 依赖完全移除，`SecurityConfig.java` 和 `SessionAuthenticationFilter.java` 删除
- [ ] SaToken 管理登录/登出/会话，Cookie 名保持 `KUROS_SESSION`
- [ ] Redis 存储 Token 和权限数据，`compose.yml` 包含 Redis 服务
- [ ] RBAC 四张表通过 Flyway V9 创建，seed 数据正确
- [ ] `StpInterfaceImpl` 从 Redis 读取角色和权限
- [ ] 验证码存储在 Redis（带 TTL），60s 频率限制生效
- [ ] `SmsSender` 接口 + `DevSmsSender` 实现（日志输出）
- [ ] 自定义线程池异步发送验证码
- [ ] CSRF 双重提交 Cookie 拦截器工作正常
- [ ] `/api/v1/auth/*` 请求/响应格式与之前完全一致
- [ ] 前端代码零改动（或仅有 Cookie 属性微调）
- [ ] 现有 37 个后端测试全部通过（适配后）
- [ ] 新增 RBAC / Redis 会话 / 验证码频率限制测试
- [ ] `npm run lint` + `npm run build` + Playwright 全套通过
- [ ] Docker Compose 启动正常（MySQL + Redis + Backend + Frontend）

## 迁移风险

| 风险 | 缓解 |
|---|---|
| SaToken Cookie 属性与 Spring Security 不同导致前端认证失败 | 配置 `cookie-httponly`/`cookie-secure`/`cookie-same-site` 与之前一致 |
| Testcontainers 在 Windows 上启动慢 | 使用 `redis:7-alpine` 轻量镜像，复用容器（`@Container` + `@Testcontainers`） |
| RBAC 迁移与现有 `users.role` 数据不一致 | V9 迁移从 `users.role` 读取现有值写入 `sys_user_role`，保证一致 |
| 移除 Spring Security 后 `@PreAuthorize` 等注解失效 | 当前代码未使用这些注解，全部用路由拦截 |
