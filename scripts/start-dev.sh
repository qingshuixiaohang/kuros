#!/usr/bin/env bash
# 内存安全启动：把 .scratch/teach/NOTES.md 的内存红线固化为可执行约束。
#
# 红线来源（2026-10-03 实测踩坑，hs_err_pid 案发现场已归档）：
#   机器 16GB，Docker Desktop WSL2 默认拿走 7.5GB，页面文件小。
#   绝不同时裸启两个 JVM —— 两个 Spring Boot 默认堆同时提交内存会触发
#   errno 1455 / AllocateHeap 失败直接崩。裸跑必须串行 + 限堆。
#
# 用法：
#   ./scripts/start-dev.sh            # 启动全部三个服务（串行 + 限堆 + 健康等待）
#   ./scripts/start-dev.sh gateway    # 只启动 gateway
#   ./scripts/start-dev.sh stop       # 停掉本脚本启动的所有服务
#
# 日志落在 scripts/logs/，PID 落在 scripts/logs/*.pid。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$REPO_ROOT/scripts/logs"

# JDK 21 —— 项目要求（Spring Boot 4 + Java 21）。PATH 里可能排着 JDK 8，
# 这里显式钉死，避免 java 解析到 1.8 导致启动即失败。
JDK21_HOME="${JDK21_HOME:-D:/java/jdk-21.0.12.1+1}"
if [[ ! -d "$JDK21_HOME" ]]; then
  echo "找不到 JDK 21：$JDK21_HOME" >&2
  echo "用 JDK21_HOME=/path/to/jdk21 覆盖。" >&2
  exit 1
fi
export JAVA_HOME="$JDK21_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# Maven 进程自身也要限堆，否则它和 Spring Boot 两个 JVM 叠加。
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx192m}"

# 各服务堆参数与 .run/*.run.xml 保持一致（改一处必须改两处）。
#   顺序即依赖顺序：backend 先起，user 次之，gateway 最后（靠 Nacos 发现前两者）。
declare -A SERVICE_PORT=(
  [backend]=8090
  [user]=8091
  [gateway]=8080
)
declare -A SERVICE_HEAP=(
  [backend]="-Xmx640m -Xms256m -XX:MaxMetaspaceSize=288m"
  [user]="-Xmx512m -Xms256m -XX:MaxMetaspaceSize=256m"
  [gateway]="-Xmx384m -XX:MaxMetaspaceSize=224m"
)
SERVICE_ORDER=(backend user gateway)

mkdir -p "$LOG_DIR"

# 健康等待：轮询 /actuator/health，超时则打日志尾部并失败退出。
wait_healthy() {
  local name="$1" port="$2" timeout="${3:-120}"
  local deadline=$((SECONDS + timeout))
  echo "  → 等待 $name (:$port) 健康，最多 ${timeout}s"
  while (( SECONDS < deadline )); do
    if curl -fsS "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1; then
      echo "  ✓ $name 健康"
      return 0
    fi
    sleep 3
  done
  echo "  ✗ $name 在 ${timeout}s 内未就绪，日志尾部：" >&2
  tail -30 "$LOG_DIR/${name}.log" >&2 || true
  return 1
}

start_one() {
  local name="$1"
  local port="${SERVICE_PORT[$name]}"
  local heap="${SERVICE_HEAP[$name]}"
  local pidfile="$LOG_DIR/${name}.pid"

  if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
    echo "$name 已在运行（PID $(cat "$pidfile")），跳过"
    return 0
  fi

  echo "启动 $name（端口 $port，堆 $heap）"
  # shellcheck disable=SC2086
  (cd "$REPO_ROOT/kuros-$name" && \
     SERVER_PORT="$port" \
     JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 $heap" \
     ./mvnw.cmd spring-boot:run >"$LOG_DIR/${name}.log" 2>&1 & \
     echo $! > "$pidfile")

  wait_healthy "$name" "$port" 180
}

stop_all() {
  for name in "${SERVICE_ORDER[@]}"; do
    local pidfile="$LOG_DIR/${name}.pid"
    if [[ -f "$pidfile" ]]; then
      local pid
      pid="$(cat "$pidfile")"
      if kill -0 "$pid" 2>/dev/null; then
        echo "停止 $name（PID $pid）"
        # mvnw 是父进程，杀整个进程树，否则 spring-boot 子 JVM 会残留
        taskkill //PID "$pid" //T //F >/dev/null 2>&1 || kill "$pid" 2>/dev/null || true
      fi
      rm -f "$pidfile"
    fi
  done
}

case "${1:-all}" in
  all)
    echo "=== 内存安全启动（串行 + 限堆，JAVA_HOME=$JAVA_HOME）==="
    for name in "${SERVICE_ORDER[@]}"; do
      start_one "$name"
    done
    echo "=== 全部就绪：gateway :8080 / backend :8090 / user :8091 ==="
    ;;
  backend|user|gateway)
    start_one "$1"
    ;;
  stop)
    stop_all
    ;;
  *)
    echo "用法：$0 [all|backend|user|gateway|stop]" >&2
    exit 2
    ;;
esac
