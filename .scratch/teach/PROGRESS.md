# 功能学习进度

更新时间：2026-10-20

## 当前状态

| 学习项 | 状态 | 证据 |
|---|---|---|
| 项目整体架构总览 | **已讲解，掌握情况未确认** | Hermes 2026-10-15：用户请求用 `how` 了解项目；随后生成《kuros-web 完整架构解释》。没有后续自测或确认记录。 |
| 点赞/收藏功能：写入链路 | **已讲过一轮，未做自测** | Hermes 2026-10-15：从一次点赞的旅程讲到 Redis 即时计数、RocketMQ 异步落库、同用户幂等、分布式锁续期、数据库复合主键、锁与事务提交顺序。用户追问并指出事务解释里的时序表达有问题，随后明确说“虽然我懂了”。 |
| 两级缓存：详情与公开资料 | **讲过，但需确认/复习基础概念** | Hermes 2026-10-15：用户指出没讲清缓存服务于什么，要求从零重讲；又追问 Caffeine“本机缓存”与正式部署的关系。解释过进程内存和 Redis 的区别，但没有明确确认理解。 |
| CDN | **了解即可，已收口** | Hermes 2026-10-15：用户判断 CDN 是了解即可的知识点，双方同意不深入。 |
| 其他用户功能链路 | **未开始** | 当前能检索到的 Hermes 历史没有更多具体功能课的完成证据。 |

注意：仓库里 19 篇 `docs/learning/` 是开发切片复盘；Hermes 的代码探索报告和 skill reference 是教学/开发参考。它们不能算作用户学完。

## 按功能学习的建议顺序

下面是根据当前产品功能整理的学习路线，不声称这是过去已经完成的历史顺序。每项只讲通该功能实际涉及的技术。

| 顺序 | 用户功能 | 重点链路 | 状态 |
|---|---|---|---|
| 0 | 项目总览 | 三个服务、前端、主要数据存储和请求入口 | 已讲；掌握情况待确认 |
| 1 | 注册、登录与保持登录 | 登录页 → Gateway → kuros-user → Sa-Token/Redis 会话与权限 | 未开始 |
| 2 | 浏览帖子、查看详情与搜索 | 前端查询 → kuros-backend → MySQL/Redis/Elasticsearch；两级缓存作为详情页支线 | 详情缓存支线讲过一部分；基础待复习，其余未开始 |
| 3 | 发布、编辑帖子与图片 | 编辑器 → 上传/媒体服务 → 帖子写入 → 搜索/Feed 后续链路 | 未开始 |
| 4 | 评论、点赞与收藏 | 写接口 → 幂等/计数 → Redis、MySQL 与异步消息 | 点赞/收藏已讲一轮；待自测 |
| 5 | 关注与关注流 | 关注关系 → kuros-user → 发帖后 Feed 扇出 → Redis ZSet 读取 | 未开始；Hermes 曾建议接着讲 Feed |
| 6 | 个人主页与作者信息 | 内容服务 → Feign → kuros-user，理解组合查询和降级 | 未开始 |
| 7 | 举报与管理处理 | 举报提交、权限校验、状态流转和管理页面 | 未开始 |

## Hermes 中找到的可用参考

这些是已有的代码理解材料，不是已完成课程：

- `G:\Hermes\skills\software-development\kuros-coding-team\references\read-path-n-plus-one.md`：读路径与 N+1 查询。
- `G:\Hermes\skills\software-development\kuros-coding-team\references\frontend-write-path-migration.md`：前端写操作与跨页面缓存一致性。
- `G:\Hermes\skills\software-development\kuros-coding-team\references\wiring-a-mock-page-to-a-real-api.md`：页面接入真实 API。
- `G:\Hermes\skills\software-development\kuros-coding-team\references\redis-zset-composite-members.md`：Feed ZSet 成员与取关清理。
- `G:\Hermes\skills\software-development\kuros-coding-team\references\count-consistency.md`：计数一致性。
- `G:\Hermes\skills\software-development\kuros-coding-team\references\best-effort-batch-operations.md`：批量操作的部分失败语义。
- `G:\Hermes\skills\software-development\kuros-coding-team\references\frontend-regression-testing.md`：前端回归验证。

## 每次学习后如何更新

先从功能和用户场景讲起，再按需解释其依赖机制。每课记录日期、讲过的链路、用户提出的疑问、是否做过自测，以及用户是否确认理解。没有确认或自测时写“已讲/待确认”，不要写成“已掌握”。不单独安排 CDN 这类低优先级基础设施深挖；按需讲清即可。

**当前建议的续学点**：先用一个具体帖子详情请求把“为什么缓存、到底缓存什么、Caffeine 和 Redis 各自在哪、单机/多实例部署下的取舍”讲顺；然后回到 Feed 功能。若用户想从完整用户旅程重排，可先从登录开始。
