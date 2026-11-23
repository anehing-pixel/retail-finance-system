# 边缘限流层（Nginx）· 说明

> 归属：部署形态说明，**不进 `docs/` 登记**（与 `interview/` 同类，不计入 `check_docs.py` 文件计数）。
> 事实源：限流口径见 [`docs/R02-总体架构设计.md`](../docs/R02-总体架构设计.md)；
> 网关侧实现见 `gateway/src/main/java/com/company/retail/gateway/ratelimit/`。

## 1. 这一层解决什么

> **定位：可选部署形态，不默认启用**（master `PROGRESS.md §9` 裁决：`nginx/` 可作独立目录并入，
> 但**不默认启用**）。网关侧的限流由 **master 的 Redis + Lua 分布式令牌桶**承担（键取 Authorization 头哈希）；
> 本层是**入口泄洪**，与网关层职责不重叠：层被限流的请求根本没进网关，网关层限的是**已进网关的同一调用方**。
> 两层同时启用时，配额关系需人工确认（边缘层按来源 IP、网关层按调用方哈希，**不是同一维度**）。

系统**未上线、无生产流量**（`PROGRESS.md §8`），所以本层不是「扛线上洪峰」，
而是把**限流能力本身**做出来并可验证：入口按来源 IP 泄洪，网关按业务路由段配额。

两层刻意用不同算法与不同维度：

| 层 | 算法 | 计数维度 | 作用 |
|---|---|---|---|
| 边缘 Nginx（本目录） | 漏桶 + 突发队列（`limit_req`） | 来源 IP | 连接级泄洪，挡住大流量与扫描 |
| 网关令牌桶（`gateway/.../ratelimit`） | 令牌桶（token bucket） | 路由段 × 客户端 IP | 业务感知配额，按路径分级 |

边缘层漏桶的特点：超出速率的请求**排队**（`burst` 队列），`nodelay` 表示突发额度直接放行、不排队；
超出 `burst` 即 429。网关令牌桶的特点：桶空即拒，并返回 `Retry-After` 告知还需等多久。

## 2. 端口与上游（已登记 `R00 §6` / `PROGRESS.md §2`）

| 入口 | 端口 | 上游 |
|---|---|---|
| 内部网关前置 | 18076 | `api-gateway` 18081 |
| 外部 DMZ 前置 | 18077 | `external-api` 18091 |

## 3. ⚠️ 必须同时配网关的可信代理网段

网关默认**不采信** `X-Forwarded-For`（`retail.ratelimit.trusted-proxy-cidrs` 默认为空），
防止「换一个头就能重置限流」。前置 Nginx 后必须显式登记：

```bash
# 同机部署
export RETAIL_RATELIMIT_TRUSTED_PROXY_CIDRS=127.0.0.1/32
# 容器部署（示例网段，按实际调整）
export RETAIL_RATELIMIT_TRUSTED_PROXY_CIDRS=172.16.0.0/12
```

不登记的后果：网关看到的所有来源都是 Nginx 自己 → 所有客户端共享一个桶 →
正常流量被误伤，而攻击流量共享额度，等于没限。

## 4. 校验与启动

```bash
nginx -t -c "$PWD/nginx/nginx.conf"       # 配置语法校验
nginx -c "$PWD/nginx/nginx.conf"          # 前台启动（另开终端停止）
```

容器方式：

```bash
docker run --rm -p 18076:18076 -p 18077:18077 \
  -v "$PWD/nginx/nginx.conf:/etc/nginx/nginx.conf:ro" \
  nginx:alpine
```

## 5. 实测记录（2026-11-23，WSL + nginx 1.28.3）

`scripts/smoke_ratelimit_edge.py` → **13/13 EXIT 0**：

| # | 断言 | 结果 |
|---|---|---|
| 1 | `nginx -t` 校验**交付配置本身** | ✅ |
| 2 | 真实 nginx 进程启动并可服务 | ✅ |
| 3 | 突发额度内放行、超额被边缘层拒绝 | ✅ |
| 4 | 边缘 429 响应体为统一结构、错误码 `RF_GATEWAY_001` | ✅ |
| 5 | 边缘 429 标记来源层 `details.layer=edge` | ✅ |
| 6 | `X-Request-Id` 透传到上游 | ✅ |
| 7 | `X-Forwarded-For` / `X-Real-IP` 透传到上游 | ✅ |
| 8–13 | **双层联动**：真实 nginx 前置真实网关 → 同客户端被**网关层**拒绝（`details.policy`）、拒绝体不含 `edge`、换客户端 IP 独立放行 | ✅ |

复现（需 nginx；WSL 侧以 root 运行，避免 pid 文件权限问题）：

```bash
wsl -u root python3 scripts/smoke_ratelimit_edge.py
```

**实测踩坑（已修进配置）**：

1. `location @ratelimit_json` **不能写在 `http` 层** → nginx 报 *"location directive is not allowed here*；
   已移入两个 `server` 块（`error_page 429 = @ratelimit_json` 仍在 http 层统一声明）。
2. `access_log` 用相对路径（`logs/…`）时，非默认 prefix 下目录不存在 → `nginx -t` 直接失败；
   已改为 `/var/log/nginx/rf_access.log`（源码编译/自定义 prefix 改回 `<prefix>/logs/rf_access.log` 并确保目录存在）。
3. `nginx -t` 会尝试写 pid 文件（默认 `/run/nginx.pid`）→ 普通用户会报 *Permission denied*；
   校验用 root 执行，或在配置里显式指定 `pid`。

## 6. 怎么验证限流真的生效（手工）

```bash
# 连续打，超过 burst=100 即应出现 429
for i in $(seq 1 130); do
  curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:18076/api/health
done | sort | uniq -c
```

边缘层 429 的响应体是统一响应结构（`code=RF_GATEWAY_001`，`details.layer=edge`）；
网关层 429 的响应体 `details.layer` 缺省、`details.policy` 为命中的路由段，
并带 `Retry-After` 与 `X-RateLimit-*` 响应头。

## 6. 参数怎么定

`nginx.conf` 里的 `rate=` / `burst=` / `limit_conn` 是**建议起始值**（⚙️ 可配置覆盖），
不是业务裁决。网关侧默认值见 `RateLimitProperties`（`retail.ratelimit.*`）。
上线前按真实流量重标：边缘层按「单机可承受并发」反推，网关层按「单段业务峰值」正推。

## 7. 已知边界

- 限流是**保护层**，不是安全边界：真正的正确性防线在各服务内（幂等唯一索引、乐观锁、fail-closed 门禁）。
- 网关令牌桶是**单实例**口径（桶在进程内存里）：网关多副本时实际放行量 ≈ 单副本额度 × 副本数。
- 本仓库**没有 Redis**，也**不引入**（`AGENTS.md` B-10 口径）；因此本层不提供跨实例全局配额。
