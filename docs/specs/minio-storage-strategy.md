# 规格：MinIO 对象存储 + 策略模式

日期：2026-09-20
ADR：待创建（0003-minio-storage-strategy）
对应小哈书：第七章（MinIO 对象存储）

## 范围

将当前本地磁盘文件存储替换为策略模式架构：`StorageStrategy`（纯 I/O）+ `MediaAssetService`（业务层）。新增 `LocalStorageStrategy`（现有逻辑提取）和 `MinIOStorageStrategy`（MinIO SDK 集成）。保持前端零改动（`/media/**` 访问路径不变）。保持单体部署。

## 非目标

- 不引入 Nacos、Gateway、Sentinel、RocketMQ、Elasticsearch
- 不拆分微服务
- 不改变前端 UI 或交互流程
- 不改变 `/api/v1/files/*` 的请求/响应格式
- 不做 MinIO 集群/分布式部署（单节点即可）
- 不做 CDN 集成（后续 Gateway 切片处理）

## 后端变更

### 1. 依赖变更（pom.xml）

新增：
- `io.minio:minio:8.5.17`（MinIO 官方 Java SDK）
- `org.testcontainers:minio`（test scope，MinIO Testcontainer）

### 2. 配置（application.properties）

```properties
# 存储策略选择：local（默认）或 minio
app.storage.type=${APP_STORAGE_TYPE:local}

# 本地存储（type=local 时生效）
app.storage.local-dir=${APP_STORAGE_LOCAL_DIR:${user.dir}/storage}
app.storage.public-base-url=${APP_STORAGE_PUBLIC_BASE_URL:http://localhost:8080}

# MinIO 存储（type=minio 时生效）
app.storage.minio.endpoint=${APP_STORAGE_MINIO_ENDPOINT:http://localhost:9000}
app.storage.minio.access-key=${APP_STORAGE_MINIO_ACCESS_KEY:minioadmin}
app.storage.minio.secret-key=${APP_STORAGE_MINIO_SECRET_KEY:minioadmin}
app.storage.minio.bucket=${APP_STORAGE_MINIO_BUCKET:kuros-media}
```

### 3. 核心类变更

| 操作 | 文件 | 说明 |
|---|---|---|
| 新增 | `storage/StorageStrategy.java` | 纯 I/O 接口：`put(key, bytes, contentType)` / `delete(key)` / `getUrl(key)` |
| 新增 | `storage/LocalStorageStrategy.java` | 本地磁盘实现（从旧 LocalImageStorageService 提取 I/O 逻辑） |
| 新增 | `storage/MinIOStorageStrategy.java` | MinIO SDK 实现 |
| 新增 | `storage/StorageConfig.java` | `@Bean` + `@ConditionalOnProperty` 选择策略 |
| 重命名 | `ImageStorageService` → `MediaAssetService` | 业务层接口（方法签名不变） |
| 重命名 | `LocalImageStorageService` → `DefaultMediaAssetService` | 业务层实现（调用 StorageStrategy + MediaAsset CRUD） |
| 修改 | `config/MediaResourceConfig.java` | 本地模式注册 ResourceHandler，MinIO 模式跳过 |
| 新增 | `web/MediaResourceController.java` | MinIO 模式下代理 `/media/**` 请求 |
| 修改 | `web/ImageUploadController.java` | 接口类型从 `ImageStorageService` 改为 `MediaAssetService` |
| 修改 | 所有引用 `ImageStorageService` 的文件 | 改为 `MediaAssetService` |

### 4. StorageStrategy 接口设计

```java
public interface StorageStrategy {
    /**
     * 存储文件。
     * @param key 存储键（UUID 文件名，如 "abc-123.png"）
     * @param bytes 文件内容
     * @param contentType MIME 类型
     */
    void put(String key, byte[] bytes, String contentType);

    /**
     * 删除文件。
     * @param key 存储键
     */
    void delete(String key);

    /**
     * 获取文件的访问 URL（用于构建响应）。
     * @param key 存储键
     * @return 公开访问 URL（如 "/media/abc-123.png"）
     */
    String getUrl(String key);

    /**
     * 读取文件内容（MinIO 模式下的代理需要）。
     * @param key 存储键
     * @return 文件字节流
     */
    byte[] get(String key);
}
```

### 5. MediaAssetService 接口（重命名自 ImageStorageService）

```java
public interface MediaAssetService {
    ImageUploadResponse store(MultipartFile file, String ownerId);
    void delete(String assetId, String ownerId);
    int cleanupTemporaryAssets(LocalDateTime cutoff);
}
```

方法签名不变，只是接口名变了。

### 6. DefaultMediaAssetService（重命名自 LocalImageStorageService）

重构后的职责：
1. 文件校验（大小/类型/Magic Number）— 保留
2. UUID 重命名 — 保留
3. 调用 `StorageStrategy.put()` 存储字节 — 新增（替代直接写磁盘）
4. MediaAsset CRUD — 保留
5. 权限校验（ownerId）— 保留

```java
@Service
public class DefaultMediaAssetService implements MediaAssetService {
    private final StorageStrategy storageStrategy;  // 注入策略
    private final MediaAssetRepository mediaAssetRepository;
    // ...

    public ImageUploadResponse store(MultipartFile file, String ownerId) {
        // 1. 校验（保留现有逻辑）
        // 2. UUID 重命名（保留）
        // 3. storageStrategy.put(key, bytes, contentType)  ← 替代 file.transferTo()
        // 4. MediaAsset 保存（保留）
        // 5. 返回 URL（storageStrategy.getUrl(key)）
    }
}
```

### 7. MediaResourceConfig 条件注册

```java
@Configuration
public class MediaResourceConfig implements WebMvcConfigurer {
    private final StorageStrategy storageStrategy;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 本地模式：ResourceHandler 直接映射磁盘目录
        // MinIO 模式：不注册 ResourceHandler，由 MediaResourceController 代理
        if (storageStrategy instanceof LocalStorageStrategy local) {
            registry.addResourceHandler("/media/**")
                    .addResourceLocations(local.root().toUri().toString());
        }
    }
}
```

### 8. MediaResourceController（MinIO 模式代理）

```java
@RestController
@ConditionalOnProperty(name = "app.storage.type", havingValue = "minio")
public class MediaResourceController {
    private final StorageStrategy storageStrategy;

    @GetMapping("/media/{key}")
    public ResponseEntity<byte[]> serve(@PathVariable String key) {
        byte[] data = storageStrategy.get(key);
        return ResponseEntity.ok()
                .contentType(MediaType.parseImage(...))
                .body(data);
    }
}
```

### 9. compose.yml 新增 MinIO 服务

```yaml
  minio:
    image: minio/minio:latest
    restart: unless-stopped
    environment:
      MINIO_ROOT_USER: ${APP_STORAGE_MINIO_ACCESS_KEY:-minioadmin}
      MINIO_ROOT_PASSWORD: ${APP_STORAGE_MINIO_SECRET_KEY:-minioadmin}
    command: server /data --console-address ":9001"
    ports:
      - "${MINIO_API_PORT:-9000}:9000"
      - "${MINIO_CONSOLE_PORT:-9001}:9001"
    volumes:
      - kuros_minio_data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  kuros_minio_data:
```

`backend` 服务新增环境变量：
```yaml
      APP_STORAGE_TYPE: ${APP_STORAGE_TYPE:-local}
      APP_STORAGE_MINIO_ENDPOINT: ${APP_STORAGE_MINIO_ENDPOINT:-http://minio:9000}
      APP_STORAGE_MINIO_ACCESS_KEY: ${APP_STORAGE_MINIO_ACCESS_KEY:-minioadmin}
      APP_STORAGE_MINIO_SECRET_KEY: ${APP_STORAGE_MINIO_SECRET_KEY:-minioadmin}
      APP_STORAGE_MINIO_BUCKET: ${APP_STORAGE_MINIO_BUCKET:-kuros-media}
```

### 10. .env.example 新增

```properties
# Storage
APP_STORAGE_TYPE=local
APP_STORAGE_MINIO_ENDPOINT=http://localhost:9000
APP_STORAGE_MINIO_ACCESS_KEY=minioadmin
APP_STORAGE_MINIO_SECRET_KEY=minioadmin
APP_STORAGE_MINIO_BUCKET=kuros-media
```

## 测试策略

### 单元测试
- `DefaultMediaAssetService`：Mock `StorageStrategy`，测试校验逻辑、MediaAsset CRUD、权限校验
- `LocalStorageStrategy`：使用临时目录测试 put/get/delete
- `MinIOStorageStrategy`：使用 Testcontainers MinIO 容器测试 put/get/delete

### 集成测试
- Testcontainers MinIO 容器：验证 MinIO SDK 交互行为
- 启动时自动创建 bucket

### 冒烟测试
- compose 全栈启动 → 上传图片 → 访问 `/media/{key}` → 验证图片正确返回
- 本地模式和 MinIO 模式分别验证

### 前端回归
- Playwright e2e 不挂（68 个测试全绿）

## 验收标准

- [ ] `StorageStrategy` 接口定义清晰（put/get/delete/getUrl）
- [ ] `LocalStorageStrategy` 提取现有 I/O 逻辑，行为不变
- [ ] `MinIOStorageStrategy` 使用 MinIO SDK 实现，Testcontainers 测试通过
- [ ] `MediaAssetService` 接口（重命名自 `ImageStorageService`）方法签名不变
- [ ] `DefaultMediaAssetService` 调用 `StorageStrategy` 而非直接操作文件系统
- [ ] `@ConditionalOnProperty("app.storage.type")` 策略切换生效
- [ ] 本地模式：ResourceHandler 映射 `/media/**`，行为与重构前一致
- [ ] MinIO 模式：Controller 代理 `/media/**`，图片正确返回
- [ ] compose.yml 新增 MinIO 服务，healthcheck 正常
- [ ] 前端代码零改动
- [ ] 现有 34 后端测试全部通过（适配后）
- [ ] 新增 MinIO 集成测试
- [ ] Playwright e2e 68 个测试全绿

## 迁移风险

| 风险 | 缓解 |
|---|---|
| 重命名接口导致大量文件改动 | 全局搜索替换，确保无遗漏 |
| MinIO 容器启动慢影响测试 | 使用 `minio/minio:latest` 轻量镜像，Testcontainers 复用 |
| MinIO 模式下图片 content-type 丢失 | `put()` 时保存 content-type 为 object metadata，`get()` 时读取 |
| 本地模式 ResourceHandler 与 MinIO Controller 路由冲突 | Controller 加 `@ConditionalOnProperty` 条件，本地模式下不加载 |

## 参考

- 犬小哈《Spring Cloud Alibaba 小哈书》第七章（MinIO 对象存储）
- MinIO 官方文档：https://min.io/docs/minio/container/index.html
- MinIO Java SDK：https://github.com/minio/minio-java
