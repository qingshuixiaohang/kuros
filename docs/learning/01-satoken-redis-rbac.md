# 切片 #1：SaToken + Redis + RBAC 分布式会话鉴权重构

> 对应小哈书：第五章（SaToken + Redis 登录、RBAC 权限模型、验证码异步发送）
> ADR：[0002-distributed-session-satoken-redis](../adr/0002-distributed-session-satoken-redis.md)
> PR：#59 | Issue：#58

---

## 一、架构迁移全景

### 重构前（旧架构）

```
浏览器 → Cookie(KUROS_SESSION) → Spring Security 过滤器链
                                      ↓
                              SessionAuthenticationFilter
                                      ↓
                          查 MySQL user_sessions 表（找 token）
                                      ↓
                          查 MySQL users 表（找用户）
                                      ↓
                              SecurityContextHolder
                                      ↓
                          业务逻辑 + 硬编码 UserRole 枚举
```

**问题**：
1. **每次认证请求查 2 次 DB**（session + user），高并发下数据库是瓶颈
2. **会话无法跨实例共享** — 多实例部署时每个实例的 `SecurityContextHolder` 独立
3. **验证码存 ConcurrentHashMap** — 服务重启全丢，多实例不共享
4. **角色硬编码枚举** — USER/ADMIN 写死在代码里，无法动态配置
5. **Spring Security 太重** — 过滤器链调试困难，拆微服务后每个服务都要复制一份

### 重构后（新架构）

```
浏览器 → Cookie(KUROS_SESSION) → SaToken SaInterceptor
                                      ↓
                              从 Redis 读 Token（O(1)）
                                      ↓
                          StpInterfaceImpl 从 Redis 读角色/权限
                                      ↓
                              业务逻辑 + RBAC 四张表
```

### 核心变化对比

| 维度 | 重构前 | 重构后 |
|---|---|---|
| 会话框架 | Spring Security | SaToken |
| Token 存储 | MySQL user_sessions | Redis |
| 每次认证成本 | 2 次 MySQL 查询 | 1 次 Redis 查询（O(1)） |
| 验证码存储 | ConcurrentHashMap（JVM 内存） | Redis（带 TTL 自动过期） |
| 权限模型 | 硬编码枚举 UserRole | RBAC 四张表 + Redis 缓存 |
| 短信发送 | 同步 | 异步线程池 + SmsSender 策略接口 |
| CSRF | Spring Security 内置 | 自实现拦截器（~50 行） |

---

## 二、关键难点解析

### 难点 1：事务边界问题（最关键的坑）

**场景**：登录时既要写 DB（创建用户/更新角色），又要写 Redis（缓存权限）。

**问题**：如果先写 Redis 再写 DB，而 DB 操作失败导致事务回滚，Redis 里的权限数据无法回滚 → 产生**孤儿缓存**（DB 里没有这个用户，但 Redis 里有他的权限）。

**解法**：`TransactionSynchronization.afterCommit()` — 延迟到 DB 事务提交成功后再写 Redis：

```java
// AuthService.java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        syncPermissionsToRedis(user.getId());
    }
});
```

**原理**：Spring 的 `TransactionSynchronization` 回调机制，`afterCommit()` 在 DB 事务成功提交后才执行，保证"DB 先落盘，Redis 再缓存"。

**如果 afterCommit 失败怎么办？** Redis 写入异常不会导致 DB 回滚（事务已提交），但缓存未写入。下次该用户请求时，`StpInterfaceImpl` 发现 Redis 未命中 → 回源 DB 查询 → 补写 Redis。这就是 Cache-Aside 模式的自愈能力。

### 难点 2：CSRF 拦截器顺序

**问题**：CSRF 拦截器与鉴权拦截器的执行顺序决定了 API 的 HTTP 状态码语义。

| 顺序 | 游客 POST 结果 | 是否正确 |
|---|---|---|
| CSRF order 0 → 鉴权 order 1 | **403**（CSRF 失败） | 错（应先告诉用户"未登录"） |
| 鉴权 order 0 → CSRF order 1 | **401**（未登录） | 对 |

**正确做法**：鉴权 order 0、CSRF order 1。只有已登录用户才触发 CSRF 校验。

### 难点 3：公开路由白名单的方法级粒度

**问题**：`SaRouter.match(路径)` 不区分 HTTP 方法。如果只写路径匹配，POST 也公开。

```java
// 错误：仅路径匹配 → POST /api/v1/posts 也公开（游客能发帖）
SaRouter.match("/api/v1/posts/**").stop();

// 正确：方法 + 路径双重匹配
SaRouter.match(SaHttpMethod.GET).match("/api/v1/posts/**").stop();
```

### 难点 4：RBAC 查询的 JPQL 陷阱

**问题**：JPQL 只能 JOIN 已映射为 JPA 实体的类，不能 JOIN 原生表名。

```java
// 错误：JPQL 不能 join 原生表名
@Query("SELECT r.roleCode FROM SysRole r JOIN sys_user_role ur ON ...")

// 正确：用 nativeQuery 写原生 SQL
@Query(value = """
    SELECT r.role_code FROM sys_role r
    JOIN sys_user_role ur ON ur.role_id = r.id
    WHERE ur.user_id = :userId
    """, nativeQuery = true)
```

### 难点 5：前端零改动的 Cookie 兼容

SaToken 配置精确对齐旧版 Cookie 属性：
```properties
sa-token.token-name=KUROS_SESSION      # Cookie 名不变
sa-token.is-read-cookie=true            # 从 Cookie 读 Token
sa-token.cookie-httponly=true           # XSS 防护
sa-token.cookie-same-site=Lax           # CSRF 防护
```

---

## 三、简历 STAR 写法

### 完整版

> **S（情境）**：项目原使用 Spring Security + MySQL Session，每次认证请求查 2 次数据库，无法水平扩展，验证码重启丢失。
>
> **T（任务）**：引入 SaToken + Redis 实现分布式会话管理，设计 RBAC 权限模型替代硬编码枚举，为后续微服务拆分打基础。
>
> **A（行动）**：
> - 完全移除 Spring Security，用 SaToken `SaInterceptor` 实现路由拦截 + 注解鉴权
> - Token 存储从 MySQL 迁移到 Redis，认证请求从 2 次 DB 查询降为 1 次 Redis O(1) 查询
> - 设计 RBAC 四张表（角色/权限/用户角色/角色权限），`StpInterface` 优先从 Redis 读权限、回源 DB 兜底
> - 登录时通过 `TransactionSynchronization.afterCommit()` 延迟同步权限到 Redis，解决 DB 回滚时 Redis 孤儿数据问题
> - 验证码迁移到 Redis + TTL 自动过期 + 60s 频率限制，短信通道通过策略模式可扩展
> - 保持 Cookie 模式不变，前端零改动
>
> **R（结果）**：34 后端测试 + 68 Playwright e2e 全绿，会话重启持久化验证通过，API 契约零变更。

### 精简版（简历一行）

> 针对单机 Session 每次认证查 2 次 DB、无法水平扩展的问题，引入 SaToken + Redis 实现分布式会话；设计 RBAC 权限模型（四表 + Redis 权限缓存），登录事务边界通过 `afterCommit()` 保证缓存一致性；完全替代 Spring Security，认证性能从 2 次 DB 降为 1 次 Redis O(1) 查询。

---

## 四、原理详解

### 4.1 SaToken 工作原理

SaToken 本质是"帮你管 Token 的 CRUD"，鉴权逻辑是你在 `SaInterceptor` 里自己写的：

```
StpUtil.login(userId)
    ↓
① 生成 Token（UUID 风格）
    ↓
② 存入 Redis：
    key = "satoken:login:token:{tokenValue}" → value = userId
    key = "satoken:login:last-activity:{tokenValue}" → value = 时间戳
    ↓
③ 自动在 HTTP 响应中 Set-Cookie: KUROS_SESSION={tokenValue}; HttpOnly; SameSite=Lax

后续请求：
浏览器自动带 Cookie → SaToken 从 Cookie 读 token → 去 Redis 查 userId → 会话成立
```

### 4.2 Redis 缓存权限的读写流程

```
写入（登录时，低频）：
  DB: sys_user_role + sys_role_permission → 查出角色/权限列表
  Redis: SET auth:roles:{userId} [USER]
         SET auth:permissions:{userId} [post:create, comment:create, ...]

读取（每次请求，高频）：
  StpInterfaceImpl.getRoleList(userId)
    → Redis SMEMBERS auth:roles:{userId} → 有就直接返回
    → 没有就回源 DB 查，然后补写 Redis（Cache-Aside 模式）
```

**设计思想**：登录是低频操作（一天一次），鉴权是高频操作（每个请求一次）。把查询成本从"每次请求"转移到"登录时一次"，是经典的**读写分离缓存策略**。

### 4.3 CSRF 双重提交 Cookie 原理

```
① GET 请求 → 后端在 Cookie 中种 XSRF-TOKEN={随机值}
② POST 请求 → 前端从 Cookie 读 XSRF-TOKEN → 放到 Header X-XSRF-TOKEN
③ 后端拦截器比对 Cookie 中的 XSRF-TOKEN 和 Header 中的 X-XSRF-TOKEN
   → 相同 → 放行（说明是同源请求）
   → 不同 → 403
```

**为什么能防 CSRF？** 攻击者的恶意网站可以自动带上 Cookie（浏览器行为），但无法读取 Cookie 的值放到 Header 中（同源策略限制）。

### 4.4 RBAC 权限模型

```
用户 ←M:N→ 角色 ←M:N→ 权限

sys_user_role:        用户 ↔ 角色（漂泊者1314 → USER）
sys_role_permission:  角色 ↔ 权限（USER → post:create, comment:create, ...）
```

**查询链路**：
- userId → sys_user_role → role_id → sys_role → role_code
- userId → sys_user_role → role_id → sys_role_permission → permission_id → sys_permission → permission_code

### 4.5 验证码服务设计

```
issue(phone):
  1. 频率限制检查：Redis EXISTS sms:limit:{phone} → 存在则拒绝
  2. 生成 6 位随机码
  3. Redis SET sms:code:{phone} {code} EX 300（5 分钟过期）
  4. Redis SET sms:limit:{phone} 1 EX 60（60 秒限制）
  5. 异步调用 SmsSender.send(phone, code)

verify(phone, code):
  1. Redis GET sms:code:{phone}
  2. 比对 → 成功则 DEL sms:code:{phone}（一次性使用，防重放）
```

---

## 五、面试八股文整理

### Q1：为什么用 SaToken 不用 Spring Security？

> Spring Security 的过滤器链模型过于重量级，对于"Cookie + Token"的简单认证场景学习成本和调试成本都很高。SaToken 以拦截器方式接入，API 简洁（`StpUtil.login()` / `StpUtil.checkLogin()`），且原生支持 Redis 存储，更适合微服务架构下的轻量级鉴权。

### Q2：为什么不用 JWT？

> JWT 无状态 Token 的致命缺陷是**无法主动失效**——一旦签发，在过期前始终有效。如果需要"踢人下线"、"实时撤销权限"、"修改密码后所有会话失效"等功能，JWT 需要额外引入黑名单机制（本质上又回到了 Redis 存储）。SaToken + Redis 天然支持：`StpUtil.kickout(userId)` 一行代码踢下线。

### Q3：Redis 和 DB 的数据一致性怎么保证？

> 采用**事务后同步**模式：DB 操作在 `@Transactional` 内执行，Redis 写入通过 `TransactionSynchronization.afterCommit()` 延迟到事务提交后。极端情况（Redis 写入失败）通过 Cache-Aside 模式兜底：下次鉴权时 Redis 未命中则回源 DB 重建缓存。

### Q4：验证码为什么存 Redis 而不是数据库？

> 1. **TTL 自动过期**：Redis 的 key 过期机制天然适合验证码，不需要定时任务清理
> 2. **性能**：Redis 是内存操作，比 MySQL 快 10-100 倍
> 3. **分布式**：多实例共享同一个 Redis，实例 A 发的验证码在实例 B 上也能验证

### Q5：CSRF 为什么用双重提交 Cookie 而不是 Token 存 Session？

> 双重提交 Cookie 的优势是**服务端无状态**——不需要在 Session 中存储 CSRF Token。对于 SaToken 这种 Token 框架，Session 概念已被弱化，双重提交模式更契合。

### Q6：RBAC 权限缓存在 Redis 里用什么数据结构？为什么？

> 用 **Redis Set**（`SADD` / `SMEMBERS`）。原因：权限列表是无序、去重的字符串集合——正好是 Set 的语义；`SMEMBERS` 一次返回全部元素；未来判断"用户是否有某权限"可用 `SISMEMBER` O(1) 判断。

### Q7：`afterCommit()` 如果执行失败怎么办？

> `afterCommit()` 内的异常不会导致 DB 回滚（事务已提交），但会导致 Redis 未写入。下次该用户请求时，`StpInterfaceImpl` 发现 Redis 缓存未命中 → 回源 DB 查询 → 补写 Redis。这就是 Cache-Aside 模式的自愈能力。

### Q8：为什么 users.role 冗余字段要保留？

> 保持现有 API 响应格式不变（`AuthUserResponse` 返回 role 字段），前端零改动。RBAC 表是权威来源，`users.role` 是快捷查询字段，登录时从 RBAC 表同步。

---

## 六、技术选型对比

### 6.1 会话框架选型

| 维度 | Spring Security | SaToken | JWT |
|---|---|---|---|
| 学习曲线 | 陡峭（过滤器链） | 平缓（StpUtil 静态方法） | 低（但生态需自建） |
| Redis 集成 | 需 spring-session-data-redis | 原生支持 | 需自建黑名单 |
| 主动踢人 | 需手动删 Session | `StpUtil.kickout()` | 需黑名单（又回到 Redis） |
| 注解鉴权 | `@PreAuthorize` | `@SaCheckRole` | 无 |
| 微服务适配 | 每个服务复制 SecurityConfig | 共享 SaToken 配置 | 每个服务验签 |
| 简历叙事 | 平淡 | 有故事 | 太常见 |

### 6.2 验证码存储选型

| 维度 | ConcurrentHashMap | Redis | MySQL |
|---|---|---|---|
| 重启丢失 | 是 | 否 | 否 |
| 多实例共享 | 否 | 是 | 是 |
| TTL 自动过期 | 否（需定时任务） | 是（原生） | 否（需定时任务） |
| 性能 | 最快（JVM） | 快（内存） | 慢（磁盘） |
| 频率限制 | 需自建 | `SET key 1 EX 60` 一行 | 需 SQL + 定时清理 |

### 6.3 权限模型选型

| 维度 | 硬编码枚举 | RBAC 四张表 |
|---|---|---|
| 动态配置 | 改代码 + 重新部署 | 数据库改一条记录 |
| 细粒度 | 粗（只有 USER/ADMIN） | 细（post:create, report:handle 等） |
| 面试价值 | 低 | 高（可聊表设计、缓存策略） |
| 扩展性 | 加新角色要改代码 | 加角色只 INSERT 一行 |

---

## 七、面试叙事模板

### 30 秒电梯版

> "我把项目的认证体系从 Spring Security 重构为 SaToken + Redis。之前每次认证请求要查两次 MySQL，重构后变成一次 Redis O(1) 查询。同时设计了 RBAC 四张表的权限模型，权限数据登录时同步到 Redis，鉴权时直接读缓存。最关键的坑是事务边界——Redis 写入必须延迟到 DB 事务提交后，否则 DB 回滚时 Redis 会有孤儿数据。"

### 2 分钟详细版

> "我们项目原来用 Spring Security + MySQL Session，每次认证请求要查 user_sessions 和 users 两张表。在往微服务演进时，这个方案有三个问题：一是性能瓶颈（每次请求 2 次 DB），二是无法水平扩展（多实例会话不共享），三是验证码存内存重启就丢。
>
> 我引入 SaToken 替代 Spring Security，Token 存 Redis，认证请求从 2 次 DB 降为 1 次 Redis。同时设计了 RBAC 四张表——角色表、权限表、用户角色关联表、角色权限关联表——替代硬编码枚举。
>
> 权限数据的读写策略是：登录时从 DB 查出角色和权限列表，写入 Redis Set；后续每次请求，SaToken 通过 StpInterface 从 Redis 读权限（O(1)），Redis 缺失时回源 DB 兜底。
>
> 最难处理的点是事务边界：login 方法加了 @Transactional，但 Redis 是非事务资源。如果先写 Redis 再写 DB，DB 回滚时 Redis 无法回滚。解法是用 Spring 的 TransactionSynchronization.afterCommit()，把 Redis 写入延迟到 DB 事务提交后执行。
>
> 最终前端零改动——Cookie 名、CSRF 逻辑、API 响应格式全部保持不变，34 个后端测试和 68 个 e2e 测试全绿。"
