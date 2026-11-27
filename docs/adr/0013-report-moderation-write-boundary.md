# 0013. 举报域审核，由帖子和评论领域服务执行内容处置

状态：已接受（依据举报处置切片补录）

## 背景

管理员确认举报时，之前由举报服务直接修改帖子或评论，再自行清缓存。这样内容领域的状态和缓存规则会分散到作者管理、管理员处置等多个入口，后续容易出现行为差异。

## 决策

举报服务维护举报记录和审核状态。确认举报时调用帖子或评论领域服务处置内容，驳回只更新举报。审核和处置保留在同一个数据库事务，详情缓存失效在提交后执行。对同一举报记录加行锁避免并发审核，处置入口允许目标已删除时继续完成确认。

例如管理员确认一条违规评论。举报服务更新审核结果，评论服务把评论标记为已删除。两步一起提交，之后清理该帖的详情缓存。如果更新评论失败，审核状态也会回滚。

## 备选与代价

由举报服务直接访问内容仓储，代码路径较短，但会重复内容状态和缓存规则。提取公共处置模块会增加依赖，还会模糊规则归属。保留内容领域写入口后，举报服务需要调用相应的领域契约。同事务保证依赖当前举报、帖子和评论都在内容服务内，若以后拆到不同数据库，要重新设计这条一致性边界。

## 验证入口

[举报服务](../../kuros-backend/src/main/java/com/kuros/kurosbackend/report/service/ContentReportService.java)、[边界测试](../../kuros-backend/src/test/java/com/kuros/kurosbackend/report/ContentReportServiceBoundaryTest.java)、[缓存失效集成测试](../../kuros-backend/src/test/java/com/kuros/kurosbackend/report/ReportDispositionCacheEvictionIntegrationTest.java)、[举报处置规格](../specs/report-moderation-boundary.md)。
