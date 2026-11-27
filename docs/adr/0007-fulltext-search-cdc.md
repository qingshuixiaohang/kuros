# 0007. 用 Elasticsearch 搜帖子，用 CDC 同步索引

日期：2026-09-23

状态：已接受

## 背景

帖子列表的关键词筛选使用 MySQL `LIKE` 匹配子串。用户搜「鸣潮攻略」时，「鸣潮的攻略」中间隔了一个字，按整段子串匹配可能搜不到。全文搜索还需要匹配正文、按相关性排序并显示命中片段。

## 决策

1. **搜索从 Elasticsearch 读。** IK 分词器把中文文本切成词，Elasticsearch 按词匹配，并给标题、摘要、正文不同的权重。搜索接口支持高亮、过滤、排序和基于上页排序值的 `search_after`。列表接口仍保留原有关键词筛选。
2. **帖子以 MySQL 为准。** 发帖、改帖或删除时，业务先提交数据库事务。Canal 读取 MySQL 的行变更日志（binlog），把订阅表的变化投到 RocketMQ，再由内容服务的 CDC 消费者更新搜索索引。CDC 指的是从数据源捕获变更。
3. **消费者回查原数据。** 例如只改一个帖子标签，消息主要告诉消费者哪个帖子变了。为了更新可搜索的整份文档，消费者用帖子 ID 回查 MySQL 中的帖子和标签，并补作者信息，再按同一文档 ID 写索引。订阅范围是 `posts` 和 `post_tags`，用户库的改名没有专门的同步触发。
4. **删除与读数。** 逻辑删除同步到索引后，查询按已发布状态过滤掉该帖。MySQL 物理删除帖子时，消费者也删除对应的索引文档。搜索结果和热度排序中的互动数字来自索引快照，详情页则另有实时计数读取路径。新变化从提交到可搜存在延迟。
5. **重建与降级。** 搜索通过索引别名读取数据；全量重建先写新的版本化索引，再切换别名。别名切换本身是原子的，重建期间并发写入的同步仍需验证。ES 不可用时，搜索返回暂不可用，正常的帖子读写仍走原有业务路径。

## 备选与代价

发帖时直接写 MySQL 和 ES 少一套同步组件，但两次写入各有失败点，而且手工改库与批处理需要另行通知索引。用业务事件也要求每条写入路径都主动发事件。CDC 能捕获订阅表内的变更，但增加 Canal、MQ、索引积压和恢复管理。短时中断能否补齐取决于出错环节、日志或消息是否仍保留，以及消费重试状态；必要时要全量重建。

## 验证入口

验证重点是中文匹配、发布到可搜的延迟、编辑与删除后的结果，以及中断后的同步。查询收益和重建耗时以指定数据集的测量为准。

[搜索实现](../../kuros-backend/src/main/java/com/kuros/kurosbackend/search/SearchQueryService.java)、[CDC 消费处理](../../kuros-backend/src/main/java/com/kuros/kurosbackend/search/cdc/PostCdcHandler.java)、[索引重建](../../kuros-backend/src/main/java/com/kuros/kurosbackend/search/SearchIndexManager.java)、[搜索规格](../specs/fulltext-search-cdc.md)、[实现复盘](../learning/14-fulltext-search-cdc.md)。
