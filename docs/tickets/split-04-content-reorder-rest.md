# Ticket: 重排 interaction + report + media 模块（Phase A-4，阶段验收）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：split-03
**阻塞**：split-05

## 范围

1. `interaction` 模块包：`PostLike`/`PostFavorite` 实体、`PostInteractionService`、`PostInteractionController`、互动 DTO 与仓储
2. `report` 模块包：`ContentReport`/`ReportReason`/`ReportStatus`/`ReportTargetType`、`ContentReportService`、`ContentReportController`/`AdminReportController`、举报 DTO 与仓储
3. `media` 模块包：`storage` 四件套（`StorageStrategy`/`LocalStorageStrategy`/`MinIOStorageStrategy`/`MediaAssetService`）、`MediaAsset` 实体与仓储、`ImageUploadController`/`MediaResourceController`
4. **Phase A 阶段验收**：全量测试绿 + Phase A 单独 commit（可独立回退）

## 验收

- [ ] 七个模块包全部就位（user/post/comment/interaction/report/media/shared）
- [ ] Phase A 提交独立成 commit，全量测试绿
- [ ] 对照检查：代码零行为变化（仅包移动与 import 更新）

## 备注

- Phase A 完成意味着"拆分时整目录端走"已就绪；Phase B 任何问题可通过二分定位（重排 or 拆分引入）
