# Issue 追踪约定

本项目使用 GitHub Issues 管理需求、技术任务和缺陷：

- 仓库：[qingshuixiaohang/kuros](https://github.com/qingshuixiaohang/kuros)
- 默认分支：`main`
- GitHub Issues 是唯一的任务记录入口；讨论结论应回写到对应 Issue。
- Pull Request 用于承载代码变更和 Review，不替代需求 Issue。

## 推荐生命周期

`needs-triage` → 规格澄清 → `ready-for-agent` → 实现与测试 → Code Review → 合并并关闭 Issue

每个实现任务尽量是一个可独立验收的垂直切片，例如“登录弹窗 + 登录接口 + 登录态展示”，而不是只按前端、后端拆成两个无法验收的任务。

## 常用命令

```powershell
gh issue list --state open
gh issue view <number> --comments
gh issue create --title "..." --body "..."
gh issue comment <number> --body "..."
gh issue close <number> --comment "..."
```

提交前先同步 Issue 状态；实现完成后在 Issue 中补充测试结果、已知限制和关联 PR。
