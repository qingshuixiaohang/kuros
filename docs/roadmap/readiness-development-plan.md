# MVP 发布加固续开发计划

更新时间：2026-10-22
基线分支：`codex/product-readiness`，`f4f156dcbe796feae1213224a2902451518086f0`
GitHub 仓库：[`qingshuixiaohang/kuros`](https://github.com/qingshuixiaohang/kuros)

## 目标与完成定义

完成当前 MVP 发布加固批次：把已创建的 #160–#167 逐项实现并验证，通过 Specs 与 Standards review，提交并按仓库授权流程创建 PR、运行 CI、处理 review、合并并更新 issue；最后补充 retro 和真实状态文档。批次验收后才可把这批工作标记完成。既有路线图的 100% 只代表旧范围，不代表本批次、生产部署或后续产品阶段已经完成。

每张工单完成条件：范围符合 GitHub issue；行为测试先 RED、实现后 GREEN；按改动面运行针对性验证；集成后再运行受影响回归；无未解决的阻断 review；issue/PR/状态文档描述与证据一致。分支合并、CI、部署或外部环境的事实必须以实际结果记录。

## 接手时先做

1. 从 `G:\kuros-web\.scratch\worktrees\product-readiness` 的 `codex/product-readiness` 继续，HEAD 应为 `f4f156dcbe796feae1213224a2902451518086f0`。不要从主目录 `G:\kuros-web` 开发：它位于另一分支且保留用户文件改动。
2. 运行 `git status --short --branch`，并核对三个相关 worktree：`product-readiness`、`readiness-publishing`、`readiness-accessibility`。所有改动都未提交；逐文件融合，不能整目录覆盖。
3. 检查 `npm ci` 是否完整结束、`front/node_modules` 是否能通过本地二进制运行；若不完整，在该 worktree 使用锁文件重跑 `npm ci --no-audit --no-fund --registry=https://registry.npmjs.org`。检查当前可用内存、后台 Maven/Node/浏览器进程、Docker 容器后再选测试。
4. 读取 `AGENTS.md`、`docs/agents/issue-tracker.md`、本计划、`docs/roadmap/current-status.md`、相关领域 spec 和 GitHub issue。遵循项目工单流程：`grill-with-docs → to-spec → to-tickets → implement + TDD → code-review`。本批次的规划和 #160–#167 issue 已创建，参见 GitHub 链接；不要重复建票。
5. 搜索 Next 16.4.2 随包文档 `front/node_modules/next/dist/docs/`，在继续 Next.js 改动前读取对应指南。

## 当前工单状态与下一步

| Issue | 当前真实状态 | 下一步与完成门槛 |
|---|---|---|
| [#159 MVP 发布加固规格](https://github.com/qingshuixiaohang/kuros/issues/159) | 规格与批次父工单；子项已有本地实现和双轴审查记录。 | 提交、PR、CI、合并与 issue 关闭仍待外部流程。 |
| [#160 生产配置与认证 fail-closed](https://github.com/qingshuixiaohang/kuros/issues/160) | 完成。生产配置校验 4/4；生产 token、验证码暴露、DevCode 与 SMS adapter fail-closed 测试通过。 | 真实 SMS provider、凭据与 HTTPS 证书仍是生产环境前置。 |
| [#161 帖子媒体生命周期](https://github.com/qingshuixiaohang/kuros/issues/161) | 完成。媒体集成测试 4/4；清理、存储删除重试与 published 引用修复合组测试通过；Flyway 到 V16。 | local/MinIO 共用可见性 seam 与实际迁移已验证。 |
| [#162 关注流关系一致性](https://github.com/qingshuixiaohang/kuros/issues/162) | 完成。关注回填、取关后首屏与旧游标、Redis 丢失重建、同分跨作者游标均有集成测试。 | 复合 member 的完整 member 游标已替代只比较 postId。 |
| [#163 内容流查询与失败反馈](https://github.com/qingshuixiaohang/kuros/issues/163) | 完成。四组 Playwright 26/26；互动 pending 禁止本地 toggle，500 显示不可用与重试。 | 最终提交前重跑相关或全量浏览器回归。 |
| [#164 发布分类与频道错误语义](https://github.com/qingshuixiaohang/kuros/issues/164) | 完成。角色、声骸、资讯、发布四组 Playwright 49/49。 | 无额外实现缺口。 |
| [#165 评论与举报分页](https://github.com/qingshuixiaohang/kuros/issues/165) | 完成。评论 20+5、排序隔离、空态、举报 51 条三页与目标链接均通过；举报上下文批量测试 6/6。 | 无 N+1；目标上下文由帖子域和评论域批量回填。 |
| [#166 登录字段与帖子菜单可访问性](https://github.com/qingshuixiaohang/kuros/issues/166) | 完成。手机号、验证码、menu/menuitem、Escape、Tab、外点关闭和焦点归还 Playwright 3/3。 | 无额外实现缺口。 |
| [#167 依赖、Actions 与状态文档](https://github.com/qingshuixiaohang/kuros/issues/167) | 完成。生产依赖 0 漏洞；Next 图片白名单测试 3/3；Actions 升到 checkout v7、setup-node v7、setup-java v6；配置测试接入 CI；current-status 已改写。 | “五作业全绿”只能在 PR CI 实际运行后确认。 |

所有 issue 正文是范围与验收标准权威来源。本表是 2026-10-22 本地实现收口快照；
PR、CI 和合并数字必须由后续外部流程产生，不能沿用本地快照冒充发布证据。

## 建议执行顺序

1. 先完成 #160 和 #161 的后端定向 RED/GREEN 与 migration/生命周期验证；每次只运行一个 Maven 测试进程。
2. 完成 #162 的 fixture GREEN 和关系变更边界测试；确认时间线游标不会因同分多作者导致遗漏/重复。
3. 合并 #164 与 #166 菜单/登录可访问性候选，先完成定向 Playwright 与静态检查。
4. 融合 #163 查询候选与 #166 菜单，再补齐互动错误和缓存一致性覆盖。每次只运行一个 Playwright runner，禁止并行共享产物目录。
5. 实施 #165 评论、举报分页。
6. 完成 #167 依赖审计、CI 接线和真实状态文档。
7. 通过所有定向验证后按内存状况分批跑回归，执行 Specs review 与 Standards review；修复发现后重跑受影响验证。最后依仓库已授权流程提交、PR、CI/review、合并，关闭对应 issue，并写 retro。

顺序可按实际依赖调整，但每次调整都记录理由；不得为追求百分比跳过验收。

## 低内存验证约束

- 主机标称 16GB，但交接前读取到的可用内存查询受沙箱 CIM 限制，当前值未知。每个批次开始前重查，不凭历史值启动全栈。
- 一次最多运行一个 Maven JVM；Maven 固定单 fork，单 fork 最大堆 768MB。JVM/Next/Playwright 不并行。
- 只启动当前测试必需的 Docker 服务。禁用整栈启动；优先纯单测、Compose config 展开与定向 Testcontainers。
- Playwright 用 `--workers=1`，单次运行，结束后确认 browser/server 进程退出。
- Next 开发服务器使用 `NODE_OPTIONS=--max-old-space-size=1024`；Playwright runner 限制为 512MB。不要同时跑 build 和 dev server。
- 前端执行建议：先单个新增 spec，再 lint、`npx tsc --noEmit`、Vitest、format check，最后单独 `next build`。
- 后端按模块/测试类串行执行，使用 `.mvn/jvm.config` 和 Surefire 限制。记录命令、退出码、通过/跳过/失败数及失败原因。
- 当前 `front/node_modules` 已确认是普通目录而不是 junction，但安装完整性未知。PID 31788 的查询当时遇到 CIM 访问错误，因此不能断言进程确实退出；接手时用进程名/命令行安全核查。

## 有意保持为未完成/环境前置的事项

- 本批次改动尚无 commit、PR 或合并证据；也没有本批次 CI 结果。
- 旧基线的历史 CI 绿灯不验证这批未提交改动。历史 129 Playwright、39 Vitest 等数字仅供对照，不能写成当前批次通过。
- 生产 SMS 供应商、凭据、HTTPS 证书、云对象存储/CDN 账号属于环境/产品决定；没有供应商协议时不得臆造发送 API。
- 第二阶段管理后台/封禁目录、材料与配队推荐、CDN 与真实短信等不自动纳入本轮 MVP。
- 外部上线应以生产环境配置、迁移执行和部署验证为依据；本地 Compose 通过不等于公网生产就绪。
