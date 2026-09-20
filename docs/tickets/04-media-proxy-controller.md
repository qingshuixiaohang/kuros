# Ticket 4: MinIO 模式下的 /media/** 代理 Controller

## 目标
MinIO 模式下，通过 Controller 代理 `/media/**` 请求，保证前端零改动。

## 变更
- 新增 `web/MediaResourceController.java`：
  - `@ConditionalOnProperty(name = "app.storage.type", havingValue = "minio")`
  - `@GetMapping("/media/{key}")` 从 StorageStrategy 读取字节流返回
  - 正确设置 Content-Type（从 MediaAsset 表读取或从 MinIO metadata 推断）
- 修改 `MediaResourceConfig.java`：本地模式注册 ResourceHandler，MinIO 模式跳过

## 阻塞
- Ticket 2（MediaAssetService 重构完成）
- Ticket 3（MinIOStorageStrategy 存在）

## 验证
- 本地模式：`/media/**` 仍走 ResourceHandler（行为不变）
- MinIO 模式：`/media/**` 走 Controller 代理，图片正确返回
