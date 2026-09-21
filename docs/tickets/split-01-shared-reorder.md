# Ticket: 重排 shared 基础设施模块（Phase A-1）

**父 Issue**：#67（切片 #10：服务拆分——用户域独立微服务）
**依赖**：无（可立即开始）
**阻塞**：split-02

## 范围

1. 把与业务域无关的基础设施收进 `shared` 模块包：全局配置（SaToken/CSRF/CORS/Sentinel/Cache/Metrics/Async/OpenAPI/Nacos 刷新等）、异常处理、健康指标、`ApiResponse`/`PageResult` 等公共结构、`DistributedLock`
2. 更新全部引用（约 11 个 Controller + 全部 Service），纯移动零行为变化
3. 验收：全量后端测试绿（数量与重排前一致）

## 验收

- [ ] `shared` 模块包建立，公共类归位
- [ ] 全量测试绿，无行为变化（不改任何逻辑代码）

## 备注

- Phase A 是"让拆分变简单"的前置投资：每次移动一批、CI 绿再动下一批（expand-contract 思路的批次化）
- `DistributedLock` 放 shared：两个域都会用它（user 的关注锁 + 内容域的互动锁）
