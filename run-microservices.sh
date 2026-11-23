#!/usr/bin/env bash
# ============================================================================
# 微服务一键编排（本地验证用）—— S1-W2 交付（R11 §3 W2 / R02 §3）
#
# 起 12 个进程 + 数据库，网关 18081 作为统一入口按路径切流；
# 外部 DMZ 18091 独立进程（/external/** 不走网关，R02 §3.1 范式 2）；
# 前端控制台 18082（serve.py，静态 + 同源代理，W-FE）；
# 外部机构模拟器 18100（node 就绪时自动拉起，RETAIL_BUREAU_CIC_URL 指向它；
#   无 node 时 CIC 回落挡板，external-simulator/simulator.js，W-SIM）。
#
# 三个本地环境障碍及处理（对齐 floorplan run-microservices.sh）：
#   1) WorkBuddy 注入 SERVER__PORT，Spring 宽松绑定会覆盖 yml 端口 → env -u 清掉；
#   2) 没起 Nacos：各服务 fail-fast=false，路由用 ROUTE_* / RETAIL_CLIENTS_* 直连覆盖；
#   3) 只有 retail-core 跑 Flyway（R00 §7），其余服务 flyway.enabled=false。
#
# 端口分配（R00 §6）：retail-core=8090 auth=8091 customer=8092 application=8093
#   underwriting=8094 bureau=8095 contract=8096 title-release=8097
#   servicing=8098 recon=8099 网关=18081 DMZ=18091 前端=18082 模拟器=18100 DB=5435
#
# 用法：
#   ./run-microservices.sh start   # 启动全部并前台保活；Ctrl+C 停止
#   ./run-microservices.sh stop    # 另开窗口停止全部
#   ./run-microservices.sh smoke   # 启动 → 走网关冒烟 + 前端页面冒烟 → 自动清理，一条命令完成
#   ./run-microservices.sh db      # 只确保数据库容器运行（幂等）
#   # 浏览器点击级 E2E（可选，需 playwright + 系统 Edge）：python frontend/browser_e2e.py
#   # 模拟对接 E2E（需模拟器在跑）：python external-simulator/demo_e2e.py
# ============================================================================
set -u
cd "$(dirname "$0")"
ROOT="$PWD"
PIDDIR="$ROOT/.microservice-pids"
LOGDIR="$ROOT/logs/microservices"
mkdir -p "$PIDDIR" "$LOGDIR"

# 冒烟解释器：Windows/Git Bash 常只有 python（无 python3），缺一不可跑冒烟
PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || PYTHON=python
# stdout 重定向/管道下 Python 输出编码回落本地代码页（cp936），冒烟含中文必崩——强制 utf-8
export PYTHONIOENCODING="${PYTHONIOENCODING:-utf-8}"

DB_CONTAINER=retail-finance-db
REDIS_CONTAINER=retail-finance-redis
export RETAIL_CUSTOMER_DEV_LOGIN="${RETAIL_CUSTOMER_DEV_LOGIN:-true}"   # 冒烟要用客户开发桥；生产默认 false
# Redis 连接参数（W-CACHE / W-RL 引入）：L2 分布式缓存 + 网关令牌桶共用同一实例
export REDIS_HOST="${REDIS_HOST:-localhost}"
export REDIS_PORT="${REDIS_PORT:-6382}"

JAR() { echo "$ROOT/$1/target/$2-1.0.0-SNAPSHOT.jar"; }

# Feign 门禁本地直连（R09 §7.2 双模 URI：给了 url 就直连，不给回退 lb://）
export RETAIL_CLIENTS_CONTRACT_URL=http://127.0.0.1:8096
export RETAIL_CLIENTS_CUSTOMER_URL=http://127.0.0.1:8092
export RETAIL_CLIENTS_UNDERWRITING_URL=http://127.0.0.1:8094

ensure_db() {
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$DB_CONTAINER"; then
    echo "[数据库] $DB_CONTAINER 已运行"
  elif docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$DB_CONTAINER"; then
    docker start "$DB_CONTAINER" >/dev/null && echo "[数据库] $DB_CONTAINER 已启动"
  else
    echo "[数据库] 创建 $DB_CONTAINER（postgres:16-alpine，宿主 5435，独立实例不与 floorplan 共库）"
    docker run -d --name "$DB_CONTAINER" \
      -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=retail_finance \
      -p 5435:5432 postgres:16-alpine \
      -c max_connections=300 >/dev/null
  fi
  # 等 PG 就绪（首次建库要初始化，最多 60s）
  for i in $(seq 1 60); do
    if docker exec "$DB_CONTAINER" pg_isready -U postgres -d retail_finance >/dev/null 2>&1; then
      echo "[数据库] 就绪（retail_finance:5435）"; return 0
    fi
    sleep 1
  done
  echo "[错误] 数据库 60s 未就绪"; return 1
}

# Redis（W-CACHE / W-RL）：两级缓存的 L2 + 网关令牌桶限流。
# 不用持久化：缓存可重建、限流状态丢了重算即可，落盘没有价值。
ensure_redis() {
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$REDIS_CONTAINER"; then
    echo "[缓存] $REDIS_CONTAINER 已运行"
  elif docker ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$REDIS_CONTAINER"; then
    docker start "$REDIS_CONTAINER" >/dev/null && echo "[缓存] $REDIS_CONTAINER 已启动"
  else
    echo "[缓存] 创建 $REDIS_CONTAINER（redis:7-alpine，宿主 $REDIS_PORT，不持久化）"
    docker run -d --name "$REDIS_CONTAINER" \
      -p "$REDIS_PORT:6379" redis:7-alpine --save "" --appendonly no >/dev/null
  fi
  for i in $(seq 1 30); do
    if docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q PONG; then
      echo "[缓存] 就绪（redis:$REDIS_PORT，${i}s）"; return 0
    fi
    sleep 1
  done
  echo "[错误] Redis 30s 未就绪"; return 1
}

start_one() {
  local name="$1" jar="$2"; shift 2
  if [ ! -f "$jar" ]; then echo "[跳过] $jar 不存在，请先 mvn -B clean package"; return 1; fi
  # env -u SERVER__PORT 是关键：否则端口被 WorkBuddy 注入值覆盖
  env -u SERVER__PORT java -Dfile.encoding=UTF-8 -jar "$jar" "$@" > "$LOGDIR/$name.log" 2>&1 &
  echo $! > "$PIDDIR/$name.pid"
  echo "[启动] $name pid=$(cat "$PIDDIR/$name.pid")"
}

# 外部机构模拟器（W-SIM）：node 可用才拉起，并把 CIC 出站切到模拟器；
# 无 node 时跳过且不导出 RETAIL_BUREAU_CIC_URL → CIC 用挡板，链路不中断
maybe_start_simulator() {
  if port_in_use 18100; then
    echo "[跳过] 18100 已有外部模拟器在跑"
    export_sim_urls
    return 0
  fi
  if ! command -v node >/dev/null 2>&1; then
    echo "[跳过] 无 node，外部通道全走挡板（未导出 SIM URL 组）"
    return 0
  fi
  node "$ROOT/external-simulator/simulator.js" > "$LOGDIR/external-simulator.log" 2>&1 &
  echo $! > "$PIDDIR/external-simulator.pid"
  echo "[启动] external-simulator pid=$(cat "$PIDDIR/external-simulator.pid")"
  for i in $(seq 1 15); do
    if curl -s -m 2 "http://127.0.0.1:18100/external-sim/health" >/dev/null 2>&1; then
      echo "[就绪] external-simulator (port 18100, ${i}s)"; break
    fi
    sleep 1
  done
  export_sim_urls
}

# W-SIM2 全通道切换：六向外部调用全部指向模拟器（各服务配置空值时回落挡板）
export_sim_urls() {
  export RETAIL_BUREAU_CIC_URL="${RETAIL_BUREAU_CIC_URL:-http://127.0.0.1:18100}"
  export RETAIL_CORE_BANK_PAY_URL="${RETAIL_CORE_BANK_PAY_URL:-http://127.0.0.1:18100}"
  export RETAIL_KYC_PROVIDER_URL="${RETAIL_KYC_PROVIDER_URL:-http://127.0.0.1:18100}"
  export RETAIL_DMS_OUTBOUND_URL="${RETAIL_DMS_OUTBOUND_URL:-http://127.0.0.1:18100}"
  export RETAIL_CONTRACT_DELIVERY_URL="${RETAIL_CONTRACT_DELIVERY_URL:-http://127.0.0.1:18100}"
}

wait_one() {
  local name="$1" marker="$2" port="$3"
  local i
  for i in $(seq 1 90); do
    if grep -q "$marker" "$LOGDIR/$name.log" 2>/dev/null; then
      echo "[就绪] $name (port $port, ${i}s)"; return 0
    fi
    if grep -qE "APPLICATION FAILED TO START|Application run failed" "$LOGDIR/$name.log" 2>/dev/null; then
      echo "[失败] $name 启动异常，最后 30 行："; tail -30 "$LOGDIR/$name.log"; return 1
    fi
    sleep 1
  done
  echo "[超时] $name 90s 未就绪，最后 30 行："; tail -30 "$LOGDIR/$name.log"; return 1
}

port_in_use() {
  netstat -ano 2>/dev/null | grep -q ":$1 .*LISTENING"
}

do_start() {
  ensure_db || return 1
  ensure_redis || return 1
  local busy=""
  for p in 8090 8091 8092 8093 8094 8095 8096 8097 8098 8099 18081 18091 18100 6382; do
    port_in_use "$p" && busy="$busy $p"
  done
  if [ -n "$busy" ]; then
    echo "[错误] 端口已被占用:$busy —— 请先执行 stop，或关闭占用进程后重试"
    return 1
  fi

  echo "=== 启动外部机构模拟器（node 可用时；CIC 出站切至模拟器）==="
  maybe_start_simulator

  echo "=== 启动 10 业务服务 + DMZ（retail-core 最先，唯一跑 Flyway）==="
  start_one retail-core "$(JAR retail-core retail-core)" || { do_stop; exit 1; }
  wait_one retail-core "Started CoreServiceApplication" 8090 || { do_stop; exit 1; }

  start_one auth-service "$(JAR auth-service retail-auth-service)"
  start_one customer-service "$(JAR customer-service retail-customer-service)"
  start_one application-service "$(JAR application-service retail-application-service)"
  start_one underwriting-service "$(JAR underwriting-service retail-underwriting-service)"
  start_one bureau-service "$(JAR bureau-service retail-bureau-service)"
  start_one contract-service "$(JAR contract-service retail-contract-service)"
  start_one title-release-service "$(JAR title-release-service retail-title-release-service)"
  start_one servicing-service "$(JAR servicing-service retail-servicing-service)"
  start_one recon-service "$(JAR recon-service retail-recon-service)"
  start_one external-api "$(JAR external-api retail-external-api)"

  wait_one auth-service "Started AuthServiceApplication" 8091 || { do_stop; exit 1; }
  wait_one customer-service "Started CustomerServiceApplication" 8092 || { do_stop; exit 1; }
  wait_one application-service "Started ApplicationServiceApplication" 8093 || { do_stop; exit 1; }
  wait_one underwriting-service "Started UnderwritingServiceApplication" 8094 || { do_stop; exit 1; }
  wait_one bureau-service "Started BureauServiceApplication" 8095 || { do_stop; exit 1; }
  wait_one contract-service "Started ContractServiceApplication" 8096 || { do_stop; exit 1; }
  wait_one title-release-service "Started TitleReleaseServiceApplication" 8097 || { do_stop; exit 1; }
  wait_one servicing-service "Started ServicingServiceApplication" 8098 || { do_stop; exit 1; }
  wait_one recon-service "Started ReconServiceApplication" 8099 || { do_stop; exit 1; }
  wait_one external-api "Started ExternalApiApplication" 18091 || { do_stop; exit 1; }

  echo "=== 启动网关（ROUTE_* 直连覆盖 lb://）==="
  env -u SERVER__PORT \
    ROUTE_CORE=http://127.0.0.1:8090 \
    ROUTE_AUTH=http://127.0.0.1:8091 \
    ROUTE_CUSTOMER=http://127.0.0.1:8092 \
    ROUTE_APPLICATION=http://127.0.0.1:8093 \
    ROUTE_UNDERWRITING=http://127.0.0.1:8094 \
    ROUTE_BUREAU=http://127.0.0.1:8095 \
    ROUTE_CONTRACT=http://127.0.0.1:8096 \
    ROUTE_TITLE_RELEASE=http://127.0.0.1:8097 \
    ROUTE_SERVICING=http://127.0.0.1:8098 \
    ROUTE_RECON=http://127.0.0.1:8099 \
    java -jar "$(JAR gateway retail-api-gateway)" > "$LOGDIR/gateway.log" 2>&1 &
  echo $! > "$PIDDIR/gateway.pid"
  wait_one gateway "Started GatewayApplication" 18081 || { do_stop; exit 1; }

  echo "=== 启动前端控制台（serve.py:18082，静态 + 同源代理 /gw /svc /cust）==="
  if port_in_use 18082; then
    echo "[跳过] 18082 已有前端在跑"
  else
    "$PYTHON" "$ROOT/frontend/serve.py" > "$LOGDIR/frontend.log" 2>&1 &
    echo $! > "$PIDDIR/frontend.pid"
    for i in $(seq 1 15); do
      if curl -s -m 2 "http://127.0.0.1:18082/" >/dev/null 2>&1; then
        echo "[就绪] frontend (port 18082, ${i}s)"; break
      fi
      sleep 1
    done
  fi

  echo ""
  echo "全部就绪。内部网关: http://127.0.0.1:18081   外部 DMZ: http://127.0.0.1:18091   前端: http://127.0.0.1:18082"
  echo "跑冒烟: bash run-microservices.sh smoke"
}

do_stop() {
  echo "=== 停止全部服务 ==="
  for pf in "$PIDDIR"/*.pid; do
    [ -f "$pf" ] || continue
    pid=$(cat "$pf")
    name=$(basename "$pf" .pid)
    if kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null && echo "[停止] $name $pid"; else echo "[已停] $name"; fi
    rm -f "$pf"
  done
}

case "${1:-}" in
  start)
    do_start || { do_stop; exit 1; }
    echo ""
    echo "服务已就绪并保持运行。按 Ctrl+C 停止全部服务。"
    trap 'echo; do_stop; exit 0' INT TERM
    wait
    ;;
  smoke)
    do_start || { do_stop; exit 1; }
    echo "=== 通过网关 18081 跑冒烟 ==="
    SMOKE_GATEWAY=http://127.0.0.1:18081 SMOKE_DMZ=http://127.0.0.1:18091 \
      DB_CONTAINER=$DB_CONTAINER "$PYTHON" scripts/smoke_w2.py
    RC=$?
    if [ $RC -eq 0 ]; then
      echo "=== 前端页面冒烟（经 18082 代理走真实链路）==="
      "$PYTHON" frontend/smoke_frontend.py
      RC=$?
    fi
    if [ $RC -eq 0 ]; then
      echo "=== MQ 通道冒烟（默认关闭 = 零回归；开启态见 MqEnvelopeLiveTest）==="
      SMOKE_GATEWAY=http://127.0.0.1:18081 DB_CONTAINER=$DB_CONTAINER "$PYTHON" scripts/smoke_mq.py
      RC=$?
    fi
    echo "=== 冒烟退出码: $RC ==="
    do_stop
    exit $RC
    ;;
  stop)
    do_stop
    ;;
  db)
    ensure_db
    ensure_redis
    ;;
  *)
    echo "用法: $0 {start|stop|smoke|db}"; exit 1
    ;;
esac
