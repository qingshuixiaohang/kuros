# Ticket 6: 测试 — MinIO Testcontainers 集成 + 现有测试适配

## 目标
新增 MinIO 集成测试，确保现有测试在重构后仍然全绿。

## 变更
- `pom.xml` 新增 `org.testcontainers:minio`（test scope）
- 新增 `MinIOStorageStrategyTest.java`：Testcontainers MinIO 容器，测试 put/get/delete
- 修改现有测试：`ImageStorageService` 引用改为 `MediaAssetService`
- 确保默认 profile（local 模式）下所有 34 个测试全绿

## 阻塞
- Ticket 2（重构完成）
- Ticket 3（MinIOStorageStrategy 存在）

## 验证
- `mvnw test` 全绿（现有 34 + 新增 MinIO 测试）
- MinIO 容器自动启动、自动创建 bucket
