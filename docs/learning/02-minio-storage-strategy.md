# 切片 #2：MinIO 对象存储 + 策略模式

> 完成时间：2026-09-21
> 涉及组件：MinIO SDK 8.5.17、Spring Boot @ConditionalOnProperty、Testcontainers GenericContainer
> 对应小哈书：第七章 对象存储

---

## 1. 这个切片干了什么？

### 解决的核心问题
原来的 `ImageStorageService` 把"文件 I/O"和"业务元数据管理"混在一起，导致：
- 存储后端（本地磁盘 vs 对象存储）无法切换
- 每次换存储介质都要改业务代码
- 不支持分布式部署（多台后端实例的文件不在同一台机器上）

### 解决方案：策略模式拆分

```
旧架构：
ImageStorageService（接口）
  └── LocalImageStorageService（本地磁盘 + MediaAsset CRUD + 文件校验）

新架构：
StorageStrategy（纯 I/O 接口：put/get/delete/getUrl）
  ├── LocalStorageStrategy（本地磁盘，开发用）
  └── MinIOStorageStrategy（MinIO SDK，生产用）

MediaAssetService（业务层接口：store/delete/cleanupTemporaryAssets）
  └── DefaultMediaAssetService（文件校验 + UUID 重命名 + MediaAsset CRUD）
        └── 注入 StorageStrategy（不关心底层是磁盘还是 MinIO）
```

### 关键设计决策

| 决策 | 选择 | 原因 |
|---|---|---|
| 接口拆分粒度 | StorageStrategy（纯 I/O）+ MediaAssetService（业务） | 单一职责，换存储不改业务 |
| 策略切换机制 | `@ConditionalOnProperty` | 比 `@Profile` 更灵活，可运行时配置 |
| `/media/**` 兼容 | 本地 ResourceHandler + MinIO Controller 代理 | 前端零改动 |
| SDK 选择 | 官方 `io.minio:minio:8.5.17` | MinIO 官方维护，S3 兼容 |
| 测试方案 | Testcontainers GenericContainer + quay.io 镜像 | 2.x 无专用 MinIO 模块 |

---

## 2. 难点与踩坑

### 难点 1：@ConditionalOnBean vs @ConditionalOnProperty
- **问题**：`MediaResourceConfig` 最初用 `@ConditionalOnBean(LocalStorageStrategy.class)`
- **风险**：组件扫描时 `config` 包可能在 `storage` 包之前处理，导致条件求值时 Bean 还未注册
- **修复**：改用 `@ConditionalOnProperty(name="app.storage.type", havingValue="local", matchIfMissing=true)`
- **教训**：`@ConditionalOnBean` 只应用于 auto-configuration 类，普通 `@Configuration` 用 `@ConditionalOnProperty`

### 难点 2：Testcontainers 2.x 没有 MinIO 专用模块
- **问题**：`org.testcontainers:minio:2.0.5` 在 Maven Central 不存在
- **修复**：改用 `GenericContainer` + 手动配置环境变量和 command
- **教训**：Testcontainers 2.x 移除了部分专用模块，但不是所有——MySQL/PostgreSQL/Redis 仍保留

### 难点 3：Docker Hub 国内拉取受限
- **问题**：`docker pull minio/minio:latest` 报 `error from registry: denied`
- **尝试**：多个镜像源（daoCloud、1ms.run、xuanyuan.me）均报 Method Not Allowed
- **最终方案**：使用 `quay.io/minio/minio:latest`（MinIO 官方 Quay.io 仓库）
- **教训**：Docker Hub 受限时优先尝试 quay.io，比配置代理/镜像源更可靠

### 难点 4：MinIO 测试的 Spring 上下文启动失败
- **问题**：`@SpringBootTest` 加载完整上下文，需要 MySQL + Redis
- **根因**：测试类缺少 `@ActiveProfiles("test")`（连 MySQL 而非 H2）和 Redis 容器
- **修复**：添加 `@ActiveProfiles("test")` + Redis GenericContainer + DynamicPropertySource
- **教训**：`@SpringBootTest` 集成测试必须提供所有依赖的基础设施

### 难点 5：MediaResourceController 的 produces 属性
- **问题**：只声明 `MediaType.IMAGE_PNG_VALUE`，JPEG/WebP 请求会 406
- **修复**：声明所有支持的类型 `{IMAGE_PNG, IMAGE_JPEG, "image/webp"}`
- **教训**：`produces` 属性影响 Spring MVC 内容协商，必须覆盖所有实际返回的类型

---

## 3. 简历 STAR 写法

### 项目经历 Bullet Point
> 设计并实现基于策略模式的文件存储抽象层（StorageStrategy），支持本地磁盘与 MinIO 对象存储无缝切换，通过 `@ConditionalOnProperty` 实现零代码切换；引入 `/media/**` 兼容层保证前端零改动；使用 Testcontainers 编写集成测试覆盖 put/get/delete 全链路。

### 技术亮点
- **策略模式解耦**：将文件 I/O 与业务逻辑分离，新增存储后端只需实现 4 个方法的接口
- **前端零改动**：两种策略的 `getUrl()` 均返回 `/media/{key}` 格式，本地走 ResourceHandler，MinIO 走 Controller 代理
- **条件装配**：`@ConditionalOnProperty` 控制策略互斥，`@ConditionalOnProperty` 控制 ResourceHandler 按需注册

---

## 4. 原理深入

### MinIO 对象存储原理
- **Bucket + Key 扁平结构**：没有目录概念，`a/b/c.png` 中的 `/` 只是 key 的一部分
- **S3 兼容协议**：底层使用 S3 API，MinIO 是 S3 的开源实现
- **putObject 流程**：客户端 → HTTP PUT → MinIO Server → 写入磁盘（纠删码保护）
- **getObject 流程**：客户端 → HTTP GET → MinIO Server → 返回字节流

### @ConditionalOnProperty 原理
```java
// Spring Boot 在 Bean 定义注册阶段检查配置属性值
// matchIfMissing=true 表示：如果配置项不存在，视为 havingValue 匹配
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)

// 等价伪代码：
if (env.getProperty("app.storage.type") == null || env.getProperty("app.storage.type").equals("local")) {
    register(thisBean);
}
```

### 策略模式的 Spring 实现
- **核心思想**：面向接口编程 + 运行时多态
- **Spring 实现**：两个 `@Component` 类通过 `@ConditionalOnProperty` 互斥，Spring 容器只有一个 `StorageStrategy` Bean
- **业务层注入**：`DefaultMediaAssetService` 构造函数注入 `StorageStrategy`，不需要知道具体实现

---

## 5. 八股文

### Q1：策略模式的核心思想是什么？
A：定义一系列算法（策略），把它们封装起来并使它们可互换。客户端通过接口调用，不依赖具体实现。符合开闭原则（新增策略不改现有代码）。

### Q2：@ConditionalOnProperty 和 @Profile 的区别？
A：`@Profile` 基于激活的 profile 名称（如 dev/prod），粒度较粗；`@ConditionalOnProperty` 基于具体配置项的值，粒度更细，可以做到同一 profile 下切换不同策略。

### Q3：为什么用 @ConditionalOnProperty 而不是 @ConditionalOnBean？
A：`@ConditionalOnBean` 依赖 Bean 注册顺序。在组件扫描场景下，`config` 包可能在 `storage` 包之前处理，导致条件求值时目标 Bean 还未注册。`@ConditionalOnProperty` 直接读配置值，不依赖 Bean 注册顺序，行为可预测。官方文档明确建议 `@ConditionalOnBean` 只用于 auto-configuration 类。

### Q4：MinIO 和 S3 的关系？
A：MinIO 是 S3 协议的开源实现。代码层面完全兼容 AWS S3 SDK，所以 MinIO 也可以用 AWS S3 SDK 来操作。区别是 MinIO 可以自建部署（私有化），S3 只能用 AWS 托管服务。

### Q5：对象存储 vs 文件存储 vs 块存储？
A：
- **块存储**（磁盘）：裸磁盘扇区，性能最高，需要自己管理文件系统
- **文件存储**（NAS）：有目录树结构，通过文件系统协议访问（NFS/SMB）
- **对象存储**（MinIO/S3）：扁平 Key-Value 结构，通过 HTTP API 访问，无限扩展，适合非结构化数据

### Q6：Testcontainers 2.x 相比 1.x 有什么变化？
A：坐标变了（`testcontainers-junit-jupiter` 替代 `junit-jupiter`）；部分专用容器模块被移除（如 MinIO），需改用 `GenericContainer`；核心类（GenericContainer、Network）仍在原包。

### Q7：为什么 Controller 代理方案性能不如 ResourceHandler？
A：ResourceHandler 直接映射磁盘路径，Spring MVC 零拷贝返回；Controller 代理需要：从 MinIO 读字节 → 加载到 JVM 内存 → 通过 HTTP 响应写回。每张图都过一次 JVM，高并发时 GC 压力大。生产环境应该用 Nginx 反向代理或预签名 URL。

---

## 6. 技术选型对比

### 存储策略 SDK 选型

| 方案 | 优点 | 缺点 | 选择 |
|---|---|---|---|
| `io.minio:minio` (官方) | 官方维护，API 简洁 | 依赖较重（OkHttp + Guava） | ✅ |
| `software.amazon.awssdk:s3` | 生态丰富，支持所有 S3 兼容服务 | 更重量级，配置复杂 | ❌ |
| `com.amazonaws:aws-java-sdk-s3` | v1 稳定 | 已停维，不推荐新项目 | ❌ |

### 镜像源选型

| 方案 | 可用性 | 选择 |
|---|---|---|
| `minio/minio` (Docker Hub) | 国内受限 | ❌ |
| `quay.io/minio/minio` (Quay.io) | 国内可用，官方维护 | ✅ |
| 第三方镜像加速 | 不稳定，经常失效 | ❌ |

---

## 7. 面试叙事模板

> "我们的社区平台需要支持图片上传。最初用本地磁盘存储，但分布式部署时文件无法共享。我设计了基于策略模式的存储抽象层——`StorageStrategy` 接口定义 4 个纯 I/O 方法，业务层 `MediaAssetService` 通过接口注入，不关心底层实现。通过 `@ConditionalOnProperty` 实现本地磁盘和 MinIO 的零代码切换。为保证前端零改动，两种模式都返回 `/media/{key}` URL，本地走 Spring MVC ResourceHandler，MinIO 走 Controller 代理。测试用 Testcontainers 启动真实 MinIO 容器验证全链路。这个设计让未来新增存储后端（如阿里云 OSS）只需实现一个接口。"
