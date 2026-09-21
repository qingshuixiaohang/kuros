# Ticket 1: 提取 StorageStrategy 接口 + LocalStorageStrategy

## 目标
从现有 `LocalImageStorageService` 中提取纯 I/O 逻辑到 `StorageStrategy` 接口和 `LocalStorageStrategy` 实现。

## 变更
- 新增 `storage/StorageStrategy.java`（接口：put/get/delete/getUrl）
- 新增 `storage/LocalStorageStrategy.java`（从 LocalImageStorageService 提取文件读写逻辑）
- `LocalStorageStrategy` 保留 `root()` 方法（MediaResourceConfig 需要）

## 阻塞
无（第一步）

## 验证
- 编译通过
- 现有测试不受影响（此时 DefaultMediaAssetService 尚未切换）
