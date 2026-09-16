# 鸣潮玩家攻略与内容社区

这是项目工作区，当前已包含 Next.js 前端 Demo 和 Spring Boot 后端基础框架。

## 目录

- `front/`：Next.js + TypeScript 前端
- `docs/`：产品计划、前端 Demo 计划和人为准备事项
- `kuros-backend/`：Spring Boot + Maven Java 后端

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
- 后端健康检查：http://localhost:8080/actuator/health

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
