# Ticket: StorageHealthIndicator 自定义健康检查

**依赖**：ticket-01
**阻塞**：ticket-03

## 范围

1. 新建 `health/StorageHealthIndicator.java`：实现 `HealthIndicator`
2. 检查 StorageStrategy 连通性（`@Component("storage")`）
3. 适配 Spring Boot 4.x 新包路径 `org.springframework.boot.health.contributor`
4. 连通返回 `UP`，异常返回 `DOWN`

## 验收

- `/actuator/health` 包含 storage 组件状态
- 存储异常时健康检查返回 DOWN
