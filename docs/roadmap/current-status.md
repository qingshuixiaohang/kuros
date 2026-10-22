# Kuros Web 当前状态

> 快照日期：2026-10-22
>
> 当前主线：`origin/main` @ `f4f156dcbe796feae1213224a2902451518086f0`
>
> 集成分支：`codex/product-readiness`，以同一提交为基线承载 #159–#167 的本地变更。
>
> 本页只记录实际执行过的本地验证。PR 编号、合并提交和 CI 结果必须等外部流程产生后
> 再补写，不能用计划或测试发现数量代替。

## 本轮目标

#159 定义的 MVP 发布加固批次包含 #160–#167。目标是收紧生产默认配置、闭环媒体生命
周期、校正关注关系、统一前端内容流错误语义、修正发布分类与详情错误状态、补齐评论和
举报分页、完成无障碍契约，并消除生产依赖与 CI runtime 告警。

## 实现状态

| Issue | 本地实现状态 | 已取得的定向证据 |
|---|---|---|
| #160 生产配置与认证 fail-closed | 完成 | 生产配置校验 4/4；生产验证码/内部 token 测试通过；`ProductionSmsSenderValidatorTest` 3/3 |
| #161 帖子媒体生命周期 | 完成 | 媒体集成测试 4/4；清理与引用修复测试 6/6；Flyway 实际迁移到 V16 |
| #162 关注关系一致性 | 完成 | `FollowingRelationshipIntegrationTest` 3/3；复合 member 游标与按作者清理合组测试通过 |
| #163 内容流查询与失败反馈 | 完成 | 关注、请求数、个人中心和写后读四组 Playwright 26/26；互动 pending 与失败态已覆盖 |
| #164 发布分类与频道错误语义 | 完成 | 角色、声骸、资讯、发布四组 Playwright 49/49 |
| #165 评论与举报分页 | 完成 | 评论追加、空态、举报三页 Playwright 3/3；举报目标上下文后端测试 6/6 |
| #166 登录字段与帖子菜单 | 完成 | 字段名称、Escape、Tab、外点关闭与焦点归还 Playwright 3/3 |
| #167 依赖、Actions 与状态 | 完成 | 生产依赖审计 0 漏洞；Actions 升至 checkout v7、setup-node v7、setup-java v6；媒体白名单测试 3/3 |

## 当前验证基线

| 检查 | 实际结果 |
|---|---|
| `npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org` | 0 vulnerabilities |
| `npm run test` | 5 个文件，42/42 通过 |
| `npx tsc --noEmit` | 通过，0 error |
| `npm run lint` | 通过 |
| `npm run format:check` | 通过 |
| `npm run build` | Next.js 16.4.2 生产构建通过 |
| `node --test scripts/production-config.test.mjs` | 4/4 通过 |
| 后端定向合组测试 | 19/19 通过 |
| Playwright 全量 | `161/161` 通过（单 worker） |

最终修订已在实际执行中完成全部 161 条 Playwright 用例。双轴审查发现的互动 pending
本地写入、菜单焦点、同分跨作者游标和媒体引用修复均已纳入该次执行。

## 发布边界

- 生产部署仍需要真实的 `SmsSender` provider 适配器和 HTTPS ingress；缺失时会 fail
  closed，不会退回固定验证码。
- `production` 配置校验只证明 Compose 和启动前置条件，不代表外部短信供应商已开通。
- Windows 上 Next standalone 产物复制需要离开文件系统沙箱执行；构建编译、类型检查和
  页面生成已成功。
- Playwright 使用单 worker 执行，避免 16GB 主机上 Next 冷编译和 Chromium 并发争用。

## 外部流程状态

- 已推送提交 `2406b00`，创建 PR #168，并关联 #160–#167。
- 首次 CI run `39803598450` 中 4 个作业通过；backend tests 未通过，结果为 240 个测试、
  1 failure、2 errors、21 skipped。
- 失败集中在两项测试夹具：关注流测试未隔离拆分后的用户目录调用；摘要测试引用的媒体
  资产未登记到数据库。对应夹具修复已完成本地静态核对，待推送并在远端 CI 复验。
- 修复后 `mvnw -DskipTests test-compile` 通过。本机定向 Testcontainers 测试仍被 Windows
  JVM 的 `Unexpected failure opening I/O selector` / `Invalid argument: connect` 阻断，
  尚未执行到断言；该环境限制不替代远端 CI 的结果。
- 提交、推送、手动 CI、Review、合并及 #160–#167 的关闭状态必须以 GitHub 实际结果
  为准；在五个 CI 作业全部通过并完成合并前，本页不能声明本轮已发布。
