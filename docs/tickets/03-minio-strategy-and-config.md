# Ticket 3: 新增 MinIOStorageStrategy + 配置 + 依赖

## 目标
实现 MinIO 存储策略，添加 SDK 依赖和配置属性。

## 变更
- `pom.xml` 新增 `io.minio:minio:8.5.17`
- `application.properties` 新增 `app.storage.type` / `app.storage.minio.*` 配置
- 新增 `storage/MinIOStorageStrategy.java`：
  - 构造时创建 `MinioClient`
  - `put()`：`minioClient.putObject()`
  - `get()`：`minioClient.getObject()` 读字节流
  - `delete()`：`minioClient.removeObject()`
  - `getUrl()`：返回 `/media/{key}`（统一路径格式）
- 新增 `storage/StorageConfig.java`：`@Bean` + `@ConditionalOnProperty` 选择策略

## 阻塞
- Ticket 1（StorageStrategy 接口必须先存在）

## 验证
- 编译通过
- `app.storage.type=local` 时注入 LocalStorageStrategy（默认行为不变）
- `app.storage.type=minio` 时注入 MinIOStorageStrategy（需要 MinIO 运行才能实际测试）
