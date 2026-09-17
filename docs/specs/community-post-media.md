# 社区帖子多图媒体闭环规格

关联任务：GitHub Issue #51（后端 #52，前端 #53）

## 1. 目标

把当前前端已经完成的帖子图片轮播扩展为真实业务闭环：登录用户可以上传多张图片、调整顺序并发布帖子；首页和帖子详情从后端读取完整图片列表；编辑帖子时可以新增、删除和重新排序图片。

本阶段继续保持单体 Spring Boot + Next.js + MySQL 架构，图片使用本地文件目录，暂不引入对象存储、CDN、消息队列或独立媒体服务。

## 2. 已确认决策

- 每个帖子最多 9 张图片，至少可以没有图片。
- 支持 PNG、JPEG/JPG、WebP；单张图片不超过 10 MB。
- 图片上传必须由服务端校验 MIME 类型、扩展名和大小，不能只依赖浏览器校验。
- 第一张图片是封面；编辑时允许拖拽调整顺序。
- 首页帖子卡片展示封面，详情页展示全部图片轮播。
- 上传流程先上传图片，再提交帖子；发布成功后图片与帖子建立关联。
- 图片响应使用带 ID 的对象结构，为后续删除、审核、压缩和 CDN 迁移保留扩展空间。
- 旧数据继续兼容 `coverImageUrl`；前端优先读取完整媒体列表。

## 3. 业务范围

### 3.1 发布

1. 用户在发布页选择图片。
2. 前端逐张或批量调用图片上传接口，展示上传中、成功和失败状态。
3. 上传成功后得到媒体 ID，前端可预览、删除和调整顺序。
4. 用户提交帖子时携带媒体 ID 列表。
5. 服务端校验媒体归属、状态、数量和顺序，创建帖子与媒体关联。

### 3.2 查看

- 帖子列表返回封面地址和完整媒体列表。
- 帖子详情返回完整媒体列表。
- 旧帖子没有媒体关联时保持无图或 Markdown 图片回退行为。
- 媒体排序按照 `sort_order` 升序返回。

### 3.3 编辑

- 作者可以删除已有图片、添加新图片和调整图片顺序。
- 更新帖子与媒体关联必须校验作者权限。
- 保存失败时不能改变原有媒体关联。
- 不提供独立的媒体排序接口，排序随帖子更新一起提交。

## 4. API 契约

### 4.1 上传图片

现有接口继续使用：

```http
POST /api/v1/files/images
Content-Type: multipart/form-data
```

响应扩展为：

```json
{
  "data": {
    "assetId": "媒体资源 ID",
    "url": "http://localhost:8080/media/uuid.webp",
    "originalName": "长离.png",
    "contentType": "image/webp",
    "size": 1048576
  }
}
```

上传资源初始状态为 `TEMPORARY`，并记录当前用户作为所有者。上传接口必须要求登录和 CSRF 校验。

### 4.2 创建和更新帖子

在现有请求体中增加 `mediaAssetIds`：

```json
{
  "type": "GUIDE",
  "category": "配队攻略",
  "title": "长离实战循环记录",
  "excerpt": "可选摘要",
  "content": "## 实战记录",
  "tags": ["长离", "实战"],
  "mediaAssetIds": ["asset-id-1", "asset-id-2"]
}
```

规则：

- 最多 9 个 ID；不能重复。
- 所有资源必须属于当前用户，且状态为 `TEMPORARY` 或当前帖子已经关联的 `ATTACHED`。
- 服务端根据资源记录生成媒体 URL，不信任客户端直接提交的 URL。
- 创建或更新使用事务；校验失败时不改变帖子和媒体关联。

### 4.3 帖子响应

列表和详情响应增加：

```json
{
  "media": [
    {
      "id": "asset-id-1",
      "url": "http://localhost:8080/media/uuid-1.webp",
      "sortOrder": 0,
      "isCover": true
    }
  ]
}
```

在过渡期间保留 `coverImageUrl`，由 `sortOrder = 0` 的媒体生成；前端可以继续兼容 `mediaUrls`，但新接口统一使用 `media`。

## 5. 数据库设计

### 5.1 `media_assets`

记录上传文件本身以及上传者，支持图片先上传、帖子后提交。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `VARCHAR(36)` | PK | 媒体资源 ID |
| `owner_id` | `VARCHAR(36)` | NOT NULL, FK `users.id` | 上传用户 |
| `storage_key` | `VARCHAR(255)` | NOT NULL, UNIQUE | 本地文件名或存储键 |
| `original_name` | `VARCHAR(255)` | NOT NULL | 原始文件名 |
| `content_type` | `VARCHAR(64)` | NOT NULL | 服务端确认的 MIME 类型 |
| `size_bytes` | `BIGINT` | NOT NULL | 文件大小 |
| `status` | `VARCHAR(32)` | NOT NULL | `TEMPORARY` / `ATTACHED` / `DELETED` |
| `created_at` | `TIMESTAMP` | NOT NULL | 上传时间 |
| `attached_at` | `TIMESTAMP` | NULL | 首次关联帖子时间 |

索引：`(owner_id, status, created_at)`。禁止把二进制图片内容直接存入 MySQL。

### 5.2 `post_media`

记录帖子与媒体资源的有序关联。

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `VARCHAR(36)` | PK | 关联记录 ID |
| `post_id` | `VARCHAR(36)` | NOT NULL, FK `posts.id` | 帖子 |
| `asset_id` | `VARCHAR(36)` | NOT NULL, FK `media_assets.id` | 媒体资源 |
| `sort_order` | `INT` | NOT NULL | 从 0 开始的展示顺序 |
| `created_at` | `TIMESTAMP` | NOT NULL | 关联时间 |

约束和索引：

- `UNIQUE(post_id, asset_id)`，避免同一图片重复关联。
- `UNIQUE(post_id, sort_order)`，保证帖子内顺序唯一。
- `INDEX(post_id, sort_order)`，优化详情和列表读取。
- `asset_id` 只允许关联一个帖子，避免一个临时上传资源被多个帖子复用。

## 6. 文件生命周期

- 上传成功：文件写入本地目录，数据库资源状态为 `TEMPORARY`。
- 发布成功：写入 `post_media`，资源状态改为 `ATTACHED`。
- 用户在发布页删除未提交图片：调用删除接口或标记为 `DELETED`，同时删除本地文件。
- 发布失败或浏览器关闭：暂时保留 `TEMPORARY` 记录；本阶段提供按创建时间清理临时资源的服务方法，定时任务可留到后续运维阶段。
- 编辑删除已关联图片：解除 `post_media` 关联并标记资源为 `DELETED`，不影响帖子正文中的历史 Markdown 图片。

## 7. 非目标

- 不实现图片裁剪、压缩、视频上传、GIF 特殊处理。
- 不引入 MinIO、OSS、CDN 或图片审核服务。
- 不调整首页 Banner 的静态 3D 轮播资源。
- 不把高清 UI 设计素材提交到 GitHub CI 测试链路。

## 8. 验收标准

1. 登录用户可上传 1 至 9 张合法图片并在发布页预览。
2. 用户可以删除和调整待发布图片顺序，第一张成为封面。
3. 发布后首页展示封面，详情页可以查看全部图片。
4. 编辑帖子后，新增、删除和排序结果可以正确保存。
5. 未登录、越权、重复媒体 ID、超过 9 张、超过 10 MB 和非法格式都会被服务端拒绝。
6. 创建或更新失败时，原帖子内容和媒体关联保持不变。
7. 旧帖子和旧 API 字段继续可读，前端不出现回归。
8. 后端单元/集成测试、前端 lint/build、Playwright 回归全部通过。
