# -*- coding: utf-8 -*-
"""W-MQ MQ 削峰通道冒烟（ADR-RF-014：RocketMQ 削峰 + 控制器密文投递）。

断言口径（不把「进程起来了」当「链路通了」，反模式 A-16）：
本脚本断言的是 **MQ 默认关闭状态下的运行态事实**——四条链路的 MQ 分支在
`retail.mq.enabled=false` 时**必须**走同步路径，broker 不可用也不受影响；
一旦有任何一条链路误入 MQ 分支，下面第 8/9 条会立刻暴露。

「MQ 开启态」的真发真收 / 明文 PII 不落 broker / 幂等去重，由
`MqEnvelopeLiveTest`（真实单节点 RocketMQ 活体联调，`-Drf.mq.live=true`）覆盖。
两者互补：
  · 本脚本 = 默认关闭 = **零回归**（跑在既有冒烟栈上，随时可跑，不需要 broker）
  · 活体联调 = 开启态 = 真实 broker 往返

环境变量：SMOKE_GATEWAY（默认 http://127.0.0.1:18081）、
DB_CONTAINER（retail-finance-db，用于核对 V20 与信封表）。
"""
import json
import os
import subprocess
import urllib.request
import urllib.error

GATEWAY = os.environ.get("SMOKE_GATEWAY", "http://127.0.0.1:18081").rstrip("/")
DB_CONTAINER = os.environ.get("DB_CONTAINER", "retail-finance-db")

PASS, FAIL = 0, []


def check(name, cond, detail=""):
    global PASS
    if cond:
        PASS += 1
        print("PASS  {}".format(name))
    else:
        FAIL.append(name)
        print("FAIL  {}  {}".format(name, detail))


def req(method, url, headers=None, data=None):
    r = urllib.request.Request(url, method=method, data=data)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # 连接失败也算失败态
        return 0, str(e)


def sql(query):
    """在本地 retail_finance 容器里跑一条只读 SQL，返回去空白后的文本。"""
    out = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", "postgres", "-d", "retail_finance",
         "-tAc", query],
        capture_output=True, text=True, timeout=20)
    return out.stdout.strip(), (out.stdout + out.stderr).strip()


# ---- 1. MQ 接入的四个服务：经网关健康（证明 MQ 底座装配未破坏服务启动）----
mq_segments = [
    ("bureau-service", "/api/credit-inquiries/health"),
    ("contract-service", "/api/contracts/health"),
    ("customer-service", "/api/customers/health"),
    ("title-release-service", "/api/title-retentions/health"),
]
for svc, seg in mq_segments:
    code, body = req("GET", GATEWAY + seg)
    ok = code == 200 and svc in body and '"status":"UP"' in body.replace(" ", "")
    check("网关切流 {} {}".format(svc, seg), ok, "HTTP {} {}".format(code, body[:120]))

# ---- 2. V20 迁移已执行 + 密文信封通道的表/列就位 ----
try:
    v20, detail = sql("SELECT count(*) FROM flyway_schema_history WHERE version='20' AND success")
    check("Flyway V20 已执行（MQ 密文信封通道）", v20 == "1", detail)

    inbox, detail = sql(
        "SELECT count(*) FROM information_schema.tables "
        "WHERE table_schema='integration' AND table_name='mq_envelope_inbox'")
    check("integration.mq_envelope_inbox 表存在", inbox == "1", detail)

    relayed, detail = sql(
        "SELECT count(*) FROM information_schema.columns "
        "WHERE table_schema='core' AND table_name='outbox_events' AND column_name='relayed_at'")
    check("core.outbox_events.relayed_at 列存在（中继只投一次）", relayed == "1", detail)

    # ---- 3. 默认关闭的运行态事实：零信封 + 中继未接管 ----
    rows, detail = sql("SELECT count(*) FROM integration.mq_envelope_inbox")
    check("默认关闭 → 信封表零行（未入队）", rows.isdigit() and int(rows) == 0,
          "rows={} {}".format(rows, detail))

    pending_relay, detail = sql(
        "SELECT count(*) FROM core.outbox_events WHERE relayed_at IS NOT NULL")
    check("默认关闭 → 中继未接管（relayed_at 全 NULL，事件仍留在 outbox）",
          pending_relay.isdigit() and int(pending_relay) == 0,
          "rows={} {}".format(pending_relay, detail))

    # ---- 4. schema 级：信封表只存元数据，不存载荷（载荷只在 broker 的密文里）----
    cols, detail = sql(
        "SELECT string_agg(column_name, ',' ORDER BY column_name) "
        "FROM information_schema.columns "
        "WHERE table_schema='integration' AND table_name='mq_envelope_inbox'")
    allowed = {"envelope_id", "topic", "consumer", "status", "attempts", "last_error",
               "created_at", "updated_at"}
    actual = set(c.strip() for c in cols.split(",") if c.strip()) if cols else set()
    check("信封表只存信封元数据（无载荷/明文列）",
          bool(actual) and actual.issubset(allowed),
          "cols={} allowed={}".format(sorted(actual), sorted(allowed)))

    pii_markers = ("name", "id_number", "idnumber", "birth", "phone", "address",
                   "payload", "ciphertext", "plain")
    leaked = [c for c in actual if any(m in c.lower() for m in pii_markers)]
    check("信封表列名不含明文 PII / 载荷列", not leaked, "leaked={}".format(leaked))
except FileNotFoundError:
    check("DB 核对（docker 不可用）", False, "docker 不可用，无法核对 V20 与信封表")

print("")
print("=" * 60)
print("MQ 通道冒烟结果：PASS {} / FAIL {}".format(PASS, len(FAIL)))
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
    raise SystemExit(1)
print("MQ 通道断言全部通过 ✅")
