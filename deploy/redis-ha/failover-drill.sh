#!/usr/bin/env bash
# ============================================================================
# Redis Sentinel 故障转移演练（R11 P0-5）
#
# 起 1 主 2 从 3 哨兵 → 停掉 master → 等 Sentinel 提升新 master → 断言主节点已切换。
# 仅供本地/类生产演练：脚本结束会 down -v 清理容器与网络。
# 用法：bash deploy/redis-ha/failover-drill.sh
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

COMPOSE="docker compose -f docker-compose.yml"
export REDIS_PASSWORD="${REDIS_PASSWORD:-rf-ha-drill-password-0123456789}"

cleanup() {
  $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

sentinel_master() {
  $COMPOSE exec -T sentinel-1 redis-cli -p 26379 \
    SENTINEL get-master-addr-by-name rfmaster 2>/dev/null \
    | tr -d '\r' | paste -sd: - | sed 's/:$//'
}

role_of() {
  $COMPOSE exec -T "$1" redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ROLE 2>/dev/null \
    | head -1 | tr -d '\r'
}

echo "=== 启动 Sentinel 拓扑（1 主 2 从 3 哨兵）==="
$COMPOSE up -d

echo "=== 等待 Sentinel 识别 master ==="
master=""
for _ in $(seq 1 60); do
  master=$(sentinel_master || true)
  [ -n "$master" ] && break
  sleep 1
done
if [ -z "$master" ]; then
  echo "DRILL_FAILED: Sentinel 60s 内未识别 master"
  exit 1
fi

# 给 Sentinel 发现两个 replica 的时间（quorum=2，需至少一个可提升副本）
sleep 10
replicas=$($COMPOSE exec -T sentinel-1 redis-cli -p 26379 SENTINEL replicas rfmaster 2>/dev/null | grep -c '^name$' || true)
echo "before master=$master replicas=${replicas}"

echo "=== 停止 master，触发故障转移 ==="
$COMPOSE stop redis-master >/dev/null

new_master="$master"
for _ in $(seq 1 90); do
  new_master=$(sentinel_master || true)
  if [ -n "$new_master" ] && [ "$new_master" != "$master" ]; then
    break
  fi
  sleep 1
done

if [ -z "$new_master" ] || [ "$new_master" = "$master" ]; then
  echo "DRILL_FAILED: 90s 内未完成主节点切换（仍为 $master）"
  exit 1
fi

promoted=""
for replica in redis-replica-1 redis-replica-2; do
  if [ "$(role_of "$replica")" = "master" ]; then
    promoted="$replica"
    break
  fi
done
if [ -z "$promoted" ]; then
  echo "DRILL_FAILED: Sentinel 报告新 master=$new_master，但两个副本均未处于 master 角色"
  exit 1
fi

echo "FAILOVER_OK old=$master new=$new_master promoted=$promoted"
