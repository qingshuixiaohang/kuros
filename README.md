# 鸣潮玩家攻略与内容社区

这是项目工作区，当前已包含 Next.js 前端 Demo 和 Spring Boot 后端基础框架。

## 目录

- `front/`：Next.js + TypeScript 前端
- `docs/`：产品计划、规格、工单、学习复盘与人为准备事项
- `kuros-backend/`：内容服务（帖子/评论/互动/举报/媒体），Spring Boot + Maven
- `kuros-user/`：用户域微服务（认证/会话/RBAC/资料/关注），切片 #10 拆出，独立库 `kuros_user`
- `kuros-gateway/`：Spring Cloud Gateway 统一入口（占 8080 正门，`lb://` 路由）
- `scripts/`：compose 冒烟与会话持久化校验脚本

## 启动前端

```powershell
cd .\front
npm install
npm run dev
```

访问 http://localhost:3000。

## Docker Compose 启动

要求安装 Docker Desktop。首次启动前可复制环境变量模板：

```powershell
Copy-Item .env.example .env
docker compose up --build
```

本地演示登录如果需要固定验证码，请在 `.env` 中填写 `APP_AUTH_DEV_CODE=123456` 并设置 `APP_AUTH_DEV_CODE_EXPOSED=true`；生产或共享环境不要开启验证码回显。

启动后访问：

- 前端：http://localhost:3000
- 网关健康检查（正门）：http://localhost:8080/actuator/health
- Nacos 控制台：http://localhost:8081（注册/配置中心）

停止服务但保留数据库和图片数据：

```powershell
docker compose down
```

查看日志：

```powershell
docker compose logs -f backend
```

重启服务：

```powershell
docker compose restart
```

MySQL 数据保存在 `kuros_mysql_data` 卷，上传图片保存在 `kuros_storage` 卷。首次启动时后端会自动执行 Flyway 迁移。当前 Compose 配置用于本地开发和演示，不包含公网 HTTPS、真实短信和生产凭据管理。

常见故障：

- 数据库连接失败：执行 `docker compose logs -f mysql backend`，确认 MySQL 已通过健康检查；首次启动需要等待迁移完成。
- Flyway 迁移失败：先查看后端日志和数据库卷状态，不要手工修改迁移文件；开发环境可在确认数据可丢失后执行 `docker compose down -v` 再重建。
- 前端请求地址错误：检查 `NEXT_PUBLIC_API_BASE_URL`，它会在前端镜像构建时写入客户端代码；修改后需要重新执行 `docker compose up -d --build frontend`。
- 浏览器跨域失败：确认 `APP_CORS_ALLOWED_ORIGIN` 与浏览器访问前端的地址完全一致。

### 服务拓扑与端口（切片 #10 微服务拆分后）

| 服务 | 宿主端口 | 职责 |
|---|---|---|
| gateway | 8080 | 统一正门，前端/冒烟的唯一入口；`lb://` 经 Nacos 路由 |
| backend | 8090 | 内容服务调试直连通道（容器内 8080） |
| user | 8091 | 用户域服务调试直连通道（容器内 8080） |
| frontend | 3000 | Next.js |
| nacos | 8848 / 9848 / 8081 | 注册 + 配置中心（OpenAPI / gRPC / 控制台） |
| mysql / redis / minio | 3307 / 6379 / 9000·9001 | 存储 |

**本地调试统一走网关 8080**：路由把 `/api/v1/auth/**` 与 `/api/v1/users/*/follow` 等用户域端点转到 `lb://kuros-user`，其余转到 `lb://kuros-backend`。**直连 8090（backend）时用户域端点返回 404 属预期**——这些端点已随切片 #10 迁出 backend、只在 kuros-user 上，必须经网关路由才能命中。帖子作者昵称等跨服务数据由 backend 经 OpenFeign 回访 kuros-user 回填（kuros-user 不可用时列表降级占位“用户”不 500、资料页 503）。

## 检查前端

```powershell
cd .\front
npm run lint
npm run build
```

后端原生启动：

```powershell
cd .\kuros-backend
.\mvnw.cmd spring-boot:run
```
