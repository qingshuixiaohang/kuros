# 角色图鉴真实化规格

## 目标

将 C 端角色图鉴从前端 Mock 数据切换为单体后端提供的真实只读数据，完成游客可以稳定使用的“角色列表 → 角色详情”垂直切片。

本规格只覆盖基础角色资料，不包含管理员编辑、培养材料、技能树、推荐配队或复杂搜索。

## 业务边界

- 游客可以浏览已启用角色的列表和详情。
- 列表支持关键词和定位筛选，默认按 `sortOrder` 升序、名称升序展示。
- 详情只返回已启用角色；不存在或未启用时统一返回 `CHARACTER_NOT_FOUND` 和 HTTP 404。
- 角色数据由 Flyway 种子迁移初始化，当前阶段不提供 C 端写接口。
- 图片字段保存可直接访问的资源 URL；当前使用项目已有本地静态素材路径。
- 前端请求失败时显示明确错误状态，不再把角色页面静默伪装成 Mock 实时数据。

## 数据模型

表：`game_characters`

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | `VARCHAR(36)` | PK | 稳定 UUID |
| `slug` | `VARCHAR(80)` | UNIQUE、NOT NULL | 前端路由标识 |
| `name` | `VARCHAR(64)` | NOT NULL | 角色名称 |
| `role` | `VARCHAR(32)` | NOT NULL | 输出、协同、辅助 |
| `rarity` | `INT` | NOT NULL、1-5 | 稀有度 |
| `attribute_name` | `VARCHAR(32)` | NOT NULL | 属性 |
| `weapon_type` | `VARCHAR(32)` | NOT NULL | 武器类型 |
| `version` | `VARCHAR(32)` | NOT NULL | 登场版本 |
| `image_url` | `VARCHAR(512)` | NOT NULL | 角色展示图 |
| `description` | `VARCHAR(500)` | NOT NULL | 基础介绍 |
| `sort_order` | `INT` | NOT NULL | 列表排序，越小越靠前 |
| `enabled` | `BOOLEAN` | NOT NULL | 是否对游客可见 |
| `created_at` | `TIMESTAMP` | NOT NULL | 创建时间 |
| `updated_at` | `TIMESTAMP` | NOT NULL | 更新时间 |

索引：`(enabled, sort_order, name)`、`(enabled, role, sort_order)`。关键词搜索使用名称、属性、武器类型和简介的数据库模糊匹配；当前不引入 Elasticsearch。

## API 契约

### `GET /api/v1/characters`

查询参数：

- `keyword`：可选，匹配名称、属性、武器类型和简介
- `role`：可选，精确匹配角色定位
- `page`：可选，默认 1
- `pageSize`：可选，默认 12，最大 50

响应沿用项目分页封装：

```json
{
  "data": [
    {
      "id": "...",
      "slug": "shorekeeper",
      "name": "守岸人",
      "role": "辅助",
      "rarity": 5,
      "attribute": "衍射",
      "weaponType": "音感仪",
      "version": "1.3",
      "imageUrl": "/art/修-守岸人 唤取动画.png",
      "description": "..."
    }
  ],
  "meta": { "page": 1, "pageSize": 12, "totalItems": 1, "totalPages": 1 }
}
```

### `GET /api/v1/characters/{slug}`

返回同一角色对象，不返回培养材料等未建模字段。

## 验收标准

1. Flyway 可以在 H2 测试库和 MySQL 开发库创建角色表并插入种子数据。
2. 游客可以访问角色列表和详情；未启用或不存在的角色不可见。
3. 角色列表的关键词、定位、分页和稳定排序行为有后端 MockMvc 测试。
4. 前端角色列表和详情使用 API 数据，不依赖 `mock.ts` 中的角色数组。
5. 前端具备加载、空数据和请求失败状态；接口失败时不得静默显示“实时” Mock 数据。
6. 前端 lint/build、后端测试和 Docker Compose 冒烟检查全部通过。

## 非目标

- 管理员角色资料 CRUD
- 角色技能、培养材料和推荐配队
- 声骸图鉴真实化
- 全文搜索、缓存和推荐排序
