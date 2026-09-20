# Ticket 7: 冒烟验证 + Playwright e2e 回归

## 目标
全栈验证：compose 启动 → 上传图片 → 访问图片 → e2e 不挂。

## 变更
- 本地模式冒烟：`docker compose up` → 上传图片 → 访问 `/media/{key}` → 图片正确
- MinIO 模式冒烟：修改 `APP_STORAGE_TYPE=minio` → 同上验证
- Playwright e2e：`npx playwright test` 全绿

## 阻塞
- Ticket 4（代理 Controller）
- Ticket 5（compose MinIO 服务）
- Ticket 6（测试通过）

## 验证
- 本地模式：34 后端 + 68 e2e 全绿
- MinIO 模式：上传 → 访问 → 图片正确显示
- 前端零改动承诺兑现
