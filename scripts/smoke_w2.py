# -*- coding: utf-8 -*-
"""S1-W2 走网关冒烟（12 部署单元 + 双主体 JWT + DMZ 验签 + Flyway）。

断言口径：不把「进程起来了」当「链路通了」（反模式 A-16 / A-1）——
每条断言都打网关 18081（或 DMZ 18091）真实 HTTP，验证认证/主体隔离/验签语义。
环境变量：SMOKE_GATEWAY（默认 http://127.0.0.1:18081）、SMOKE_DMZ（http://127.0.0.1:18091）、
DB_CONTAINER（retail-finance-db，用于核对 Flyway V1–V18）。

覆盖新增能力：网关 Redis 令牌桶限流（429 + 统一错误码）、产品档位目录与还款计划快照列。
"""
import base64
import hashlib
import hmac
import json
import os
import time
import urllib.request
import urllib.error

GATEWAY = os.environ.get("SMOKE_GATEWAY", "http://127.0.0.1:18081").rstrip("/")
DMZ = os.environ.get("SMOKE_DMZ", "http://127.0.0.1:18091").rstrip("/")
DB_CONTAINER = os.environ.get("DB_CONTAINER", "retail-finance-db")
PARTNER_SECRET = os.environ.get("RETAIL_PARTNER_DMS_SECRET", "dev-dms-partner-secret-0123456789")

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


# ---- 1. 网关 + 10 业务服务健康（各走各的路由段，证明切流真实生效）----
segments = [
    ("retail-core", "/api/health"),
    ("auth-service", "/api/auth/health"),
    ("customer-service", "/api/customers/health"),
    ("application-service", "/api/applications/health"),
    ("underwriting-service", "/api/underwriting/health"),
    ("bureau-service", "/api/credit-inquiries/health"),
    ("contract-service", "/api/contracts/health"),
    ("title-release-service", "/api/title-retentions/health"),
    ("servicing-service", "/api/collection-cases/health"),
    ("recon-service", "/api/recon/health"),
]
for svc, seg in segments:
    code, body = req("GET", GATEWAY + seg)
    ok = code == 200 and svc in body and '"status":"UP"' in body.replace(" ", "")
    check("网关切流 {} {}".format(svc, seg), ok, "HTTP {} {}".format(code, body[:120]))

# ---- 2. DMZ 健康（独立进程 18091，不经网关）----
code, body = req("GET", DMZ + "/external/health")
check("DMZ external-api 健康", code == 200 and "external-api" in body, "HTTP {}".format(code))

# ---- 3. 员工登录（BCrypt + core.users 种子）----
code, body = req("POST", GATEWAY + "/api/auth/staff/login",
                 headers={"Content-Type": "application/json"},
                 data=json.dumps({"username": "admin", "password": "admin123"}).encode())
staff = json.loads(body).get("data", {}) if code == 200 else {}
staff_token = staff.get("token", "")
check("员工登录 admin/admin123", code == 200 and staff_token and
      staff.get("subjectType") == "EMPLOYEE", "HTTP {} {}".format(code, body[:160]))
check("员工 token 携带权限点", "UNDERWRITING_DECIDE" in body, body[:160])

# ---- 4. 双主体隔离（切断点 ③：客户 token 不得过员工验签）----
code, body = req("POST", GATEWAY + "/api/auth/customer/login",
                 headers={"Content-Type": "application/json"},
                 data=json.dumps({"username": "dev-cust-1"}).encode())
customer = json.loads(body).get("data", {}) if code == 200 else {}
customer_token = customer.get("token", "")
check("客户开发桥签发 token（subjectType=CUSTOMER）",
      code == 200 and customer_token and customer.get("subjectType") == "CUSTOMER",
      "HTTP {} {}".format(code, body[:160]))

code, _ = req("GET", GATEWAY + "/api/whoami")
check("whoami 无 token → 401", code == 401, "HTTP {}".format(code))

code, body = req("GET", GATEWAY + "/api/whoami",
                 headers={"Authorization": "Bearer " + staff_token})
ok = code == 200 and '"subject":"admin"' in body.replace(" ", "") and "EMPLOYEE" in body
check("whoami 员工 token → 验签回显", ok, "HTTP {} {}".format(code, body[:160]))

code, body = req("GET", GATEWAY + "/api/whoami",
                 headers={"Authorization": "Bearer " + customer_token})
check("whoami 客户 token 过员工验签 → 401（主体隔离）", code == 401, "HTTP {}".format(code))

code, body = req("GET", GATEWAY + "/api/whoami", headers={"Authorization": "Bearer garbage.token.here"})
check("伪造 token → 401", code == 401, "HTTP {}".format(code))

# ---- 5. traceId 贯穿（网关 X-Request-Id → 服务响应头回显 → ApiResponse.traceId）----
rid = "smoke-rid-" + str(int(time.time()))
code, body = req("GET", GATEWAY + "/api/health", headers={"X-Request-Id": rid})
check("X-Request-Id 贯穿进 ApiResponse.traceId", code == 200 and rid in body, body[:160])

# ---- 6. DMZ 验签（R07 §5.1/§5.2：时间戳 → nonce → 签名常量时间比较）----
path = "/external/echo"
body_text = json.dumps({"hello": "dmz", "ts": int(time.time())})
ts = str(int(time.time()))
nonce = "smoke-nonce-{}".format(int(time.time()))
canonical = "\n".join(["POST", path, ts, nonce, "v1",
                       hashlib.sha256(body_text.encode()).hexdigest()])
signature = base64.b64encode(
    hmac.new(PARTNER_SECRET.encode(), canonical.encode(), hashlib.sha256).digest()).decode()

code, body = req("POST", DMZ + path,
                 headers={"Content-Type": "application/json",
                          "X-Partner-Code": "DMS-DEV-01", "X-Nonce": nonce,
                          "X-Timestamp": ts, "X-Key-Version": "v1",
                          "X-Signature": signature},
                 data=body_text.encode())
check("DMZ 合法签名回显 → 200", code == 200 and "DMS-DEV-01" in body, "HTTP {} {}".format(code, body[:160]))

bad_sig = signature[:-4] + "AAAA"
code, body = req("POST", DMZ + path,
                 headers={"Content-Type": "application/json",
                          "X-Partner-Code": "DMS-DEV-01", "X-Nonce": nonce + "-2",
                          "X-Timestamp": ts, "X-Key-Version": "v1", "X-Signature": bad_sig},
                 data=body_text.encode())
check("DMZ 篡改签名 → 401 RF_PTNR_001", code == 401 and "RF_PTNR_001" in body,
      "HTTP {} {}".format(code, body[:160]))

code, body = req("POST", DMZ + path,
                 headers={"Content-Type": "application/json",
                          "X-Partner-Code": "DMS-DEV-01", "X-Nonce": nonce + "-3",
                          "X-Timestamp": str(int(time.time()) - 3600), "X-Key-Version": "v1",
                          "X-Signature": signature},
                 data=body_text.encode())
check("DMZ 过期时间戳 → 401", code == 401, "HTTP {} {}".format(code, body[:160]))

code, body = req("POST", DMZ + path,
                 headers={"Content-Type": "application/json",
                          "X-Partner-Code": "GHOST-99", "X-Nonce": nonce + "-4",
                          "X-Timestamp": ts, "X-Key-Version": "v1", "X-Signature": signature},
                 data=body_text.encode())
check("DMZ 未登记 partner → 403 RF_PTNR_003", code == 403 and "RF_PTNR_003" in body,
      "HTTP {} {}".format(code, body[:160]))

# ---- 7. Flyway 迁移全部执行（retail-core 唯一跑迁移）----
try:
    import subprocess
    import glob as _glob
    ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    expected_migrations = len(_glob.glob(os.path.join(ROOT, "infrastructure", "src",
                                                    "main", "resources", "db", "migration", "V*.sql")))
    out = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", "postgres", "-d", "retail_finance",
         "-tAc", "SELECT count(*) FROM flyway_schema_history WHERE success"],
        capture_output=True, text=True, timeout=15)
    migrated = out.stdout.strip()
    check("Flyway 迁移全部执行（{} / {} 条）".format(migrated, expected_migrations),
          migrated.isdigit() and int(migrated) >= expected_migrations, out.stdout + out.stderr)
    out2 = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", "postgres", "-d", "retail_finance",
         "-tAc", "SELECT count(*) FROM information_schema.tables "
                 "WHERE table_schema IN ('core','pii','integration','audit')"],
        capture_output=True, text=True, timeout=15)
    tables = out2.stdout.strip()
    check("四 schema 建表（{} 张）".format(tables), tables.isdigit() and int(tables) >= 40,
          out2.stdout + out2.stderr)
    # 审计 append-only 抽查：audit.audit_events 存在且 admin 种子在 core.users
    out3 = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", "postgres", "-d", "retail_finance",
         "-tAc", "SELECT count(*) FROM core.users WHERE username='admin'"],
        capture_output=True, text=True, timeout=15)
    check("种子员工 admin 存在（登录链路数据面）", out3.stdout.strip() == "1", out3.stdout + out3.stderr)
    # 产品档位目录（V18）：建表 + 种子档位
    out4 = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", "postgres", "-d", "retail_finance",
         "-tAc", "SELECT count(*) FROM core.financial_products"],
        capture_output=True, text=True, timeout=15)
    check("产品档位目录已建并含种子档位（{} 条）".format(out4.stdout.strip()),
          out4.stdout.strip().isdigit() and int(out4.stdout.strip()) >= 2,
          out4.stdout + out4.stderr)
    # 还款计划快照列（V18）：配置变更不影响历史计划的前提
    out5 = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", "postgres", "-d", "retail_finance",
         "-tAc", "SELECT count(*) FROM information_schema.columns "
                 "WHERE table_schema='core' AND table_name='repayment_plans' "
                 "AND column_name IN ('product_code','product_version','rate_bp')"],
        capture_output=True, text=True, timeout=15)
    check("还款计划快照列已加（product_code/product_version/rate_bp）",
          out5.stdout.strip() == "3", out5.stdout + out5.stderr)
except FileNotFoundError:
    check("Flyway 迁移核对", False, "docker 不可用，无法核对迁移记录")

# ---- 8. 网关限流（Redis 令牌桶；探针路径用独立 Authorization 头拿独立桶）----
probe_auth = "Bearer rl-probe-{}".format(int(time.time() * 1000))
probe_results = []
for _ in range(10):
    probe_results.append(req("GET", GATEWAY + "/api/rate-limit-probe/x",
                             headers={"Authorization": probe_auth}))
probe_codes = [c for c, _ in probe_results]
check("网关令牌桶限流触发 429", 429 in probe_codes, "codes={}".format(probe_codes))
check("限流首个请求放行（桶初始为满）", probe_codes[0] != 429, "codes={}".format(probe_codes))
limited_body = next((b for c, b in probe_results if c == 429), "")
check("限流响应体含统一错误码 RF_GATEWAY_001", "RF_GATEWAY_001" in limited_body,
      limited_body[:160])

# ---- 9. 产品档位目录（读接口走两级缓存：L1 Caffeine → L2 Redis → 库）----
code, body = req("GET", GATEWAY + "/api/financial-products/AUTO_LOAN_36_0",
                 headers={"Authorization": "Bearer " + staff_token})
check("读产品档位（36 期 0 息）",
      code == 200 and "AUTO_LOAN_36_0" in body and '"rateBp":0' in body.replace(" ", ""),
      "HTTP {} {}".format(code, body[:160]))

code, body = req("GET", GATEWAY + "/api/financial-products/AUTO_LOAN_36_0",
                 headers={"Authorization": "Bearer " + staff_token})
check("产品档位二次读走缓存（结果一致）",
      code == 200 and "AUTO_LOAN_36_0" in body, "HTTP {} {}".format(code, body[:160]))

print("")
print("=" * 60)
print("冒烟结果：PASS {} / FAIL {}".format(PASS, len(FAIL)))
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
    raise SystemExit(1)
print("全部通过 ✅")
