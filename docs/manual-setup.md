# 需要人为准备的内容

## 现在不需要手动搭建

当前 Demo 的 Next.js 工程、TypeScript、Tailwind、ESLint、图标库、表单/校验/查询依赖、组件目录、Mock 数据和本地图片素材都已初始化。

启动方式：

```powershell
cd .\front
npm run dev
```

浏览器打开 `http://localhost:3000`。

## 你需要尽早确认的 4 件事

1. **产品名称与版权边界**：当前使用“潮汐研究所”作为临时品牌，素材均为原创氛围图，不使用官方 Logo/角色图。请确认是否保留。
2. **后端联调地址**：Java Gateway 准备好后提供开发地址，例如 `http://localhost:8080`，以及是否跨域、认证使用 Cookie 还是 Authorization header。
3. **接口字段**：至少先确定 `GET /api/posts` 的分页结构和 `Post` 字段，前端再把 Mock 数据无痛替换成 API。
4. **素材来源**：Demo 可以继续使用 `front/public/art`；正式上线前再决定 CDN、图片压缩、审核和 MinIO 上传策略。

## 你可以手动做、但不会阻塞我开发的事情

- 准备真实品牌 Logo、favicon、角色/声骸素材。
- 建立 Java 后端仓库和 Swagger/OpenAPI。
- 确定域名、部署方式和图片存储。

这些属于产品/后端输入，不是前端基础框架搭建。前端 Demo 可先用当前 Mock 数据继续推进。
