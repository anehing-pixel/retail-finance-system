#!/usr/bin/env bash
# 开发常驻栈：拉起 12 个部署单元并 wait（供 E2E 联调用，进程树归属本后台任务）。
# 用法：bash scripts/dev-stack.sh  （作为 run_in_background 任务运行）
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
# 本地开发密钥（R11 P0-8）：仅本地显式注入并激活 dev profile；
# 生产不 source 本文件，服务侧无默认值，缺失即启动失败。
. "$ROOT/scripts/dev-secrets.sh"
LOGS="$ROOT/logs/microservices"
mkdir -p "$LOGS"

# 无 Nacos 环境：Feign 门禁走直连 URL 覆盖（与 run-microservices.sh 同口径）；开客户开发桥
export SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false
export RETAIL_CUSTOMER_DEV_LOGIN="${RETAIL_CUSTOMER_DEV_LOGIN:-true}"
export RETAIL_CLIENTS_CONTRACT_URL=http://127.0.0.1:8096
export RETAIL_CLIENTS_CUSTOMER_URL=http://127.0.0.1:8092
export RETAIL_CLIENTS_UNDERWRITING_URL=http://127.0.0.1:8094
# 网关路由直连（无 Nacos 时 lb:// 不可用）
export ROUTE_CORE=http://127.0.0.1:8090
export ROUTE_AUTH=http://127.0.0.1:8091
export ROUTE_CUSTOMER=http://127.0.0.1:8092
export ROUTE_APPLICATION=http://127.0.0.1:8093
export ROUTE_UNDERWRITING=http://127.0.0.1:8094
export ROUTE_BUREAU=http://127.0.0.1:8095
export ROUTE_CONTRACT=http://127.0.0.1:8096
export ROUTE_TITLE_RELEASE=http://127.0.0.1:8097
export ROUTE_SERVICING=http://127.0.0.1:8098
export ROUTE_RECON=http://127.0.0.1:8099

start() { # name port
  local jar
  jar=$(ls "$ROOT/$1/target/"*.jar 2>/dev/null | grep -v original | head -1)
  if [ -z "$jar" ]; then echo "[缺 jar] $1"; return 1; fi
  env -u SERVER__PORT -u SERVER_PORT java -jar "$jar" --server.port="$2" \
    > "$LOGS/$1.log" 2> "$LOGS/$1.err.log" &
  echo "[启动] $1 pid=$! port=$2"
}

start retail-core 8090
sleep 3
start auth-service 8091
start customer-service 8092
start application-service 8093
start underwriting-service 8094
start bureau-service 8095
start contract-service 8096
start title-release-service 8097
start servicing-service 8098
start recon-service 8099
start external-api 18091
start gateway 18081

echo "[dev-stack] 全部已派发，wait 保持常驻…"
wait
