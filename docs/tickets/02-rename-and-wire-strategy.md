# Ticket 2: 重命名 ImageStorageService → MediaAssetService

## 目标
将业务层接口和实现类重命名，体现职责变化。

## 变更
- `ImageStorageService.java` → `MediaAssetService.java`（接口名变更，方法签名不变）
- `LocalImageStorageService.java` → `DefaultMediaAssetService.java`（实现类重命名）
- 修改 `DefaultMediaAssetService` 内部：注入 `StorageStrategy` 替代直接操作文件系统
- 修改 `ImageUploadController`：类型引用改为 `MediaAssetService`
- 修改 `MediaResourceConfig`：依赖改为 `StorageStrategy`
- 全局搜索替换所有 `ImageStorageService` 引用

## 阻塞
- Ticket 1（StorageStrategy 接口必须先存在）

## 验证
- 编译通过
- 34 个现有测试全绿（行为不变）
