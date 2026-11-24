# Redis Sentinel 高可用拓扑（网关限流）

> 适用：生产/类生产环境的网关限流 Redis（`ADR-RF-012` 用途白名单 ① 只读配置缓存 / ② 网关限流状态）。
> 对应门禁：`R11 P0-5`（生产 fail-closed + Redis 高可用部署 + 失败告警）。
> **本目录是部署模板，不是自动化生产发布**；实际发布按你们的环境（K8s / 虚机 / 托管 Redis）落地。

## 1. 拓扑

```
                 ┌──────────────┐
                 │  api-gateway │  SPRING_DATA_REDIS_SENTINEL_MASTER=rfmaster
                 │ (Lettuce)    │  SPRING_DATA_REDIS_SENTINEL_NODES=sentinel-1:26379,...
                 └──────┬───────┘
                        │ 读 Sentinel 拓扑
        ┌───────────────┼────────────────┐
        ▼               ▼                ▼
  sentinel-1       sentinel-2       sentinel-3      quorum = 2
   :26379            :26380            :26381
        └───────────────┼────────────────┘
                        │ 监控 / 自动提升
              ┌─────────┴─────────┐
              ▼                   ▼
        redis-master  ──────►  redis-replica-1
              │                   │
              └──────────────►  redis-replica-2
```

- 1 master + 2 replicas + 3 sentinels（quorum=2，可容忍 1 个 Sentinel 或 1 个副本故障）。
- 数据节点**强制口令**（`REDIS_PASSWORD`）；Sentinel 通过 `sentinel auth-pass` 认证数据节点。
- 不持久化（`--save "" --appendonly no`）：限流状态可重建，落盘没有价值（`ADR-RF-012`）。
- 生产建议：私有网络 + 防火墙只放行网关网段；数据节点口令由密钥托管注入；如需 TLS，在 Redis 7 上启用 `tls-port` 并给 Gateway 配 `spring.data.redis.ssl.enabled=true`。

## 2. 启动 / 停止

```bash
export REDIS_PASSWORD='请替换为密钥托管的强口令'
docker compose -f deploy/redis-ha/docker-compose.yml up -d
docker compose -f deploy/redis-ha/docker-compose.yml ps
```

停止：

```bash
docker compose -f deploy/redis-ha/docker-compose.yml down -v
```

> `REDIS_PASSWORD` 未设置时 Compose 会直接报错退出（fail-closed，不给默认口令）。

## 3. 网关接入

网关**默认仍是 `redis` 模式**，只需把连接方式从单实例 host/port 换成 Sentinel：

```bash
export SPRING_DATA_REDIS_SENTINEL_MASTER=rfmaster
export SPRING_DATA_REDIS_SENTINEL_NODES='sentinel-1:26379,sentinel-2:26380,sentinel-3:26381'
export SPRING_DATA_REDIS_PASSWORD="$REDIS_PASSWORD"
# 生产必须保持默认 fail-closed；本地开发才显式置 true（scripts/dev-secrets.sh）
export RETAIL_GATEWAY_RATE_LIMIT_FAIL_OPEN=false
```

- `spring.data.redis.sentinel.master/nodes` 是 Spring Boot 标准配置，网关代码不需要改。
- 网关容器必须与 Sentinel 在同一 Docker 网络（或能解析 Sentinel 地址）。若网关跑在宿主机、Sentinel 在容器内，
  **不要**把容器内 IP 直接配给宿主机——用宿主端口映射 + Sentinel `announce-ip/announce-port`，
  或直接使用云厂商托管 Redis（推荐）。
- 本地单实例（6382）仍走 `REDIS_HOST/REDIS_PORT`，不受本模板影响。

## 4. 故障转移演练

```bash
bash deploy/redis-ha/failover-drill.sh
```

判据：脚本输出 `FAILOVER_OK old=... new=... promoted=redis-replica-*`，退出码 0。
脚本会停掉 master、等待 Sentinel 提升副本，并校验被提升副本的 `ROLE` 为 `master`，结束后自动清理。

## 5. 失败告警（网关日志标记）

网关在 Redis 不可用时输出 **ERROR** 级稳定标记（实例级 10s 节流，避免故障期日志洪泛）：

```
[ALERT_RATE_LIMIT_REDIS_UNAVAILABLE] mode=redis failMode=CLOSED path=/api/... err=...
```

| 字段 | 含义 | 告警建议 |
|---|---|---|
| `failMode=CLOSED` | 生产默认：请求被 503 拒绝 | **P1 / 立即呼叫**（入口不可用） |
| `failMode=OPEN` | 本地 dev 显式开启：限流被绕过 | **P2 / 安全告警**（生产不应出现） |

日志平台规则示例（Loki / LogQL）：

```logql
count_over_time({app="api-gateway"} |= "ALERT_RATE_LIMIT_REDIS_UNAVAILABLE" [1m]) > 0
```

同时建议监控 Sentinel：

- `SENTINEL get-master-addr-by-name rfmaster` 在 1 分钟内变化 → 通知；
- `SENTINEL replicas rfmaster` 少于 2 个 → 告警（副本不足）；
- 任一 Sentinel 容器重启 → 通知。

## 6. 边界

- 本模板不替代生产密钥托管与网络策略；口令、TLS、防火墙由部署环境落实。
- 不引入 Redis Cluster / 跨地域复制；单 master + Sentinel 已满足当前网关限流的状态可用性。
- 限流状态**不持久化**：Redis 全量重启后桶状态从零开始，属于可接受的行为（保护层，不是账务真相源）。
