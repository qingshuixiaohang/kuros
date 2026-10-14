#!/usr/bin/env bash
# 推送健康检查：git 的反馈不可信（会说 "Everything up-to-date" 而远端 SHA 未变），
# 所以用服务端 API 做唯一真相源。退出码 0 = 推送真的落地，1 = 没有。
#
# 用法: bash push-health-check.sh [branch]
# 默认分支 main。

set -u

BRANCH="${1:-main}"
REPO="qingshuixiaohang/kuros"
MARKER="[push-health]"

cd /g/kuros-web || exit 2

export https_proxy=http://127.0.0.1:12450
export http_proxy=http://127.0.0.1:12450

LOCAL=$(git rev-parse "HEAD" 2>/dev/null)
if [ -z "$LOCAL" ]; then
  echo "$MARKER FAIL: 取不到本地 HEAD" >&2
  exit 2
fi

REMOTE=$(gh api "repos/$REPO/commits/$BRANCH" --jq '.sha' 2>/dev/null)
if [ -z "$REMOTE" ]; then
  echo "$MARKER FAIL: 取不到远端 SHA（gh api 失败，代理或网络问题）" >&2
  exit 2
fi

echo "$MARKER local  HEAD    = $LOCAL"
echo "$MARKER remote $BRANCH = $REMOTE"

if [ "$LOCAL" = "$REMOTE" ]; then
  echo "$MARKER OK: 本地与远端一致"
  exit 0
fi

echo "$MARKER RED: 远端落后 $(git rev-list --count "$REMOTE".."$LOCAL" 2>/dev/null || echo '?') 个提交"
exit 1
