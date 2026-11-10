# Kuros 文档导航

| 想查什么 | 从哪里看 |
| --- | --- |
| 本地怎么启动、开发模式和演示模式有什么区别 | [项目首页](../README.md#本地启动两种模式2026-10-03-起) |
| 架构为什么这么选，替代方案和代价是什么 | [架构决策 ADR](adr/) |
| 实现时遇到了什么问题、怎么验证 | [学习复盘](learning/README.md) |
| 某项需求原来怎么定、接口约定是什么 | [技术规格](specs/)、[API 契约](api-contract.md) |
| 早期任务是怎么拆的 | [历史工单](tickets/) |
| 最近交付了什么、还有什么没做 | [GitHub Issues](https://github.com/qingshuixiaohang/kuros/issues)、[已合并 PR](https://github.com/qingshuixiaohang/kuros/pulls?q=is%3Apr+is%3Amerged) |
| 怎么准备、派发和审查开发任务 | [工单工作流](agents/issue-tracker.md) |

ADR、规格、工单和复盘可能讲到同一个功能。以搜索为例：[ADR](adr/0007-fulltext-search-cdc.md)解释为什么选 Elasticsearch 和 CDC，[规格](specs/fulltext-search-cdc.md)记录交付边界，[复盘](learning/14-fulltext-search-cdc.md)记录实现与验证。复习选型时先读 ADR，需要细节再跟链接。

早期的 Nacos、Gateway 和用户服务拆分规格沿用旧路径：[Nacos](slice8-nacos-spec.md)、[Gateway](slice9-gateway-spec.md)、[服务拆分](slice10-service-split-spec.md)。旧规格、工单和复盘保留当时的背景；以实际代码、合并记录和最新验证判断当前状态。

新增 ADR 时用 `编号. 决策标题`，依次写背景、决策、备选方案、影响与代价、验证。没有实测数据时，注明还没验证的部分。
