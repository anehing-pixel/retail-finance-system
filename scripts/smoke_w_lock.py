# -*- coding: utf-8 -*-
"""W-LOCK 分布式锁专项冒烟（真实 Redis + 真实 PG，全走网关 18081）。

对应 ADR-RF-013 / R10 I 组断言：
  A37 并发同客户同车进件 -> 只受理一条（库内 1 行、事件 1 条）
  A38 同幂等键并发 -> 后到者重放返回首次结果（库内 1 行）
  A39 Redis 不可达 -> fail-closed 拒绝写（503 / RF_LOCK_002），不落库；恢复后可写
  A40 同一业务回执重复投递（外部方换 nonce 重推）-> 幂等（库内 1 行、事件 1 条）

环境变量：
  SMOKE_GATEWAY（默认 http://127.0.0.1:18081）
  SMOKE_DMZ（默认 http://127.0.0.1:18091）
  DB_CONTAINER（默认 retail-finance-db）
  LOCK_REDIS_CONTAINER（默认 retail-finance-lock-redis）
  PARTNER_REG_SECRET（默认 dev-registry-sim-secret-0123456789）

断言口径：与 smoke_w2.py / e2e_w8.py 一致（check + PASS/FAIL 汇总）；业务失败不中断，尽量跑完。
"""
import base64
import hashlib
import hmac
import io
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GATEWAY = os.environ.get("SMOKE_GATEWAY", "http://127.0.0.1:18081").rstrip("/")
DMZ = os.environ.get("SMOKE_DMZ", "http://127.0.0.1:18091").rstrip("/")
DB_CONTAINER = os.environ.get("DB_CONTAINER", "retail-finance-db")
DB_NAME = os.environ.get("DB_NAME", "retail_finance")
LOCK_REDIS = os.environ.get("LOCK_REDIS_CONTAINER", "retail-finance-lock-redis")
PARTNER_REG_SECRET = os.environ.get("PARTNER_REG_SECRET", "dev-registry-sim-secret-0123456789")
NL = chr(10)

TS = int(time.time())
TAG = "lock{}-{}".format(TS, os.getpid())
PASS, FAIL, SKIP = 0, [], []


def check(name, cond, detail=""):
    global PASS
    if cond:
        PASS += 1
        print("PASS  {}".format(name))
    else:
        FAIL.append(name)
        print("FAIL  {}  {}".format(name, detail))


def skip(name, reason):
    SKIP.append(name)
    print("SKIP  {}  {}".format(name, reason))


def req(method, url, headers=None, data=None, timeout=30):
    r = urllib.request.Request(url, method=method, data=data)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, str(e)


def api(method, path, body=None, token=None, idem=None, timeout=30):
    url = path if path.startswith("http") else GATEWAY + path
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    if idem:
        h["Idempotency-Key"] = idem
    payload = json.dumps(body).encode() if body is not None else None
    code, text = req(method, url, h, payload, timeout=timeout)
    try:
        obj = json.loads(text)
    except Exception:
        obj = None
    data = obj.get("data") if isinstance(obj, dict) else None
    return code, data, text


def psql(sql, timeout=30):
    out = subprocess.run(["docker", "exec", DB_CONTAINER, "psql", "-U", "postgres",
                           "-d", DB_NAME, "-tAc", sql],
                          capture_output=True, text=True, timeout=timeout)
    if out.returncode != 0:
        raise RuntimeError("psql 失败: {} {}".format(out.stdout[:200], out.stderr[:200]))
    return out.stdout.strip()


def docker(*args, timeout=120):
    return subprocess.run(["docker"] + list(args), capture_output=True, text=True, timeout=timeout)


def wait_event(event_type, expect=None, timeout=30, interval=1.0):
    expect = dict((k, str(v)) for k, v in (expect or {}).items())
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            rows = psql("SELECT payload FROM core.outbox_events WHERE event_type = '{}' "
                        "ORDER BY event_id DESC LIMIT 50".format(event_type))
        except Exception:
            rows = ""
        for line in rows.splitlines():
            line = line.strip()
            if not line:
                continue
            if all(v in line for v in expect.values()):
                try:
                    return json.loads(line)
                except Exception:
                    return {"raw": line}
        time.sleep(interval)
    return None


def count_event(event_type, aggregate_key):
    """按聚合键精确计数（aggregate_key = applicationId / releaseOrderId），避免 LIKE 误命中。"""
    try:
        return int(psql("SELECT count(*) FROM core.outbox_events WHERE event_type = '{}' "
                        "AND aggregate_key = '{}'".format(event_type, aggregate_key)) or 0)
    except Exception:
        return -1


# ---------- 0. 前置：员工登录 + 客户 + 核验 + 同意 ----------
code, data, text = api("POST", "/api/auth/staff/login",
                        {"username": "admin", "password": "admin123"})
staff_token = (data or {}).get("token", "")
if code != 200 or not staff_token:
    print("前置失败：员工登录 HTTP {} {}".format(code, text[:200]))
    print("PASS 0 / FAIL 0")
    sys.exit(1)

VIN = "LOCKVIN-" + TAG
VIN2 = "LOCKVIN2-" + TAG
CASH = 1200000
code, data, text = api("POST", "/api/customers", {
    "externalRef": "EXT-" + TAG, "name": "锁测试客户", "nameKana": "カギテスト",
    "address": "東京都千代田区丸の内1-1-1", "birthDate": "1990-01-01",
    "phone": "090{:08d}".format(TS % 100000000), "employer": "LOCK株式会社",
    "annualIncome": "6000000"}, token=staff_token)
customer_id = (data or {}).get("customerId")
if not customer_id:
    print("前置失败：客户注册 HTTP {} {}".format(code, text[:200]))
    sys.exit(1)

code, data, text = api("POST", "/api/identity-verifications",
                        {"customerId": customer_id}, token=staff_token)
verification_id = (data or {}).get("verificationId")
if verification_id:
    api("POST", "/api/identity-verifications/{}/documents".format(verification_id),
        {"documentType": "DRIVER_LICENSE", "attachmentId": None,
         "expiryDate": "2030-12-31", "maskedNumber": "****1234"}, token=staff_token)
    api("POST", "/api/identity-verifications/{}/start".format(verification_id), {},
        token=staff_token)
    api("POST", "/api/identity-verifications/{}/review".format(verification_id),
        {"approve": True, "reviewerId": "admin", "note": "lock-smoke"}, token=staff_token)

expires = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(TS + 90 * 86400))
code, data, text = api("POST", "/api/consents", {
    "customerId": customer_id, "purpose": "CREDIT_INQUIRY", "consentVersion": "v1",
    "channel": "PORTAL", "evidenceAttachmentId": None, "expiresAt": expires},
    token=staff_token)
consent_id = (data or {}).get("consentId")
if not consent_id:
    print("前置失败：同意取得 HTTP {} {}".format(code, text[:200]))
    sys.exit(1)


def app_body(vin):
    return {"customerId": customer_id, "dealerPartnerId": None,
            "transactionScheme": "INSTALLMENT_CREDIT", "channel": "PORTAL",
            "consentId": consent_id, "vin": vin, "model": "LOCK-MODEL",
            "cashPrice": CASH, "accessoriesAmount": 0}


# ---------- A. 并发进件去重（A37 / A38）----------
N = 5


def submit_once(i, vin, key):
    return api("POST", "/api/applications", app_body(vin), token=staff_token, idem=key)


with ThreadPoolExecutor(max_workers=N) as pool:
    futures = [pool.submit(submit_once, i, VIN, TAG + "-app-" + str(i)) for i in range(N)]
    results = [f.result() for f in futures]

ok_codes = [r for r in results if r[0] == 200]
conflict_codes = [r for r in results if r[0] == 409]
winner = ok_codes[0] if ok_codes else None
check("A37 并发 {} 条同客户同车进件 -> 恰好受理 1 条".format(N),
      len(ok_codes) == 1 and len(conflict_codes) == N - 1,
      "codes={}".format([r[0] for r in results]))
check("A37 其余并发请求被 RF_APP_001 拒绝（去重窗口）",
      all("RF_APP_001" in r[2] for r in conflict_codes),
      "texts={}".format([r[2][:120] for r in conflict_codes[:2]]))
app_id = (winner[1] or {}).get("applicationId") if winner else None
check("A37 受理工单带 applicationId 与申请编号",
      bool(app_id) and bool((winner[1] or {}).get("applicationNo")) if winner else False,
      "winner={}".format(str(winner[1])[:160] if winner else "none"))
row_n = psql("SELECT count(*) FROM core.applications a "
             "JOIN core.application_items i ON i.application_id = a.application_id "
             "WHERE a.customer_id={} AND i.vin='{}'".format(customer_id, VIN))
check("A37 库内该客户该车申请行数 = 1", row_n == "1", "rows={}".format(row_n))
ev_n = count_event("APPLICATION_SUBMITTED", str(app_id)) if app_id else -1
check("A37 APPLICATION_SUBMITTED 事件数 = 1（不重复发事件）", ev_n == 1, "events={}".format(ev_n))

# 同幂等键并发 -> 重放（A38）
same_key = TAG + "-same-key"
with ThreadPoolExecutor(max_workers=2) as pool:
    futures = [pool.submit(submit_once, i, VIN2, same_key) for i in range(2)]
    same_results = [f.result() for f in futures]
same_ok = [r for r in same_results if r[0] == 200]
ids = set((r[1] or {}).get("applicationId") for r in same_ok)
replayed = [r for r in same_ok if (r[1] or {}).get("replayed") is True]
check("A38 同幂等键并发 -> 都返回 200 且同一申请号", len(same_ok) == 2 and len(ids) == 1,
      "codes={} ids={}".format([r[0] for r in same_results], ids))
check("A38 后到者标记 replayed=true（重放不重复受理）", len(replayed) >= 1,
      "replayed={}".format(len(replayed)))
row_n2 = psql("SELECT count(*) FROM core.applications WHERE idempotency_key='{}'".format(same_key))
check("A38 同幂等键库内行数 = 1", row_n2 == "1", "rows={}".format(row_n2))

# ---------- B. 锁不可用 fail-closed（A39）----------
docker("stop", LOCK_REDIS)
time.sleep(1)
VIN3 = "LOCKVIN3-" + TAG
code, data, text = api("POST", "/api/applications", app_body(VIN3), token=staff_token,
                       idem=TAG + "-redis-down", timeout=60)
check("A39 Redis 不可达 -> 503 RF_LOCK_002（fail-closed 拒写）",
      code == 503 and "RF_LOCK_002" in text, "HTTP {} {}".format(code, text[:200]))
row_n3 = psql("SELECT count(*) FROM core.application_items WHERE vin='{}'".format(VIN3))
check("A39 拒写期间未落库（行数 = 0）", row_n3 == "0", "rows={}".format(row_n3))
docker("start", LOCK_REDIS)
for _ in range(30):
    if docker("exec", LOCK_REDIS, "redis-cli", "ping").stdout.strip() == "PONG":
        break
    time.sleep(1)
time.sleep(3)
VIN4 = "LOCKVIN4-" + TAG
code = 0
text = ""
for attempt in range(6):
    code, data, text = api("POST", "/api/applications", app_body(VIN4), token=staff_token,
                           idem=TAG + "-redis-up", timeout=60)
    if code == 200:
        break
    time.sleep(5)
check("A39 Redis 恢复后写路径恢复（200）", code == 200,
      "HTTP {} {}".format(code, text[:200]))

# ---------- C. 回执幂等（A40）----------
LOAN_ID = 990000 + (TS % 1000)
code, data, text = api("POST", "/api/title-retentions",
                       {"loanId": LOAN_ID, "vin": "LOCK-TITLE-" + TAG, "contractualOnly": False},
                       token=staff_token)
title_id = (data or {}).get("titleId")
check("C 前置：所有权保留登记成功", code == 200 and bool(title_id),
      "HTTP {} {}".format(code, text[:200]))
code, data, text = api("POST", "/api/release-orders",
                       {"loanId": LOAN_ID, "loanSettledEvidenceRef": "EVID-" + TAG},
                       token=staff_token)
order_id = (data or {}).get("releaseOrderId") or (data or {}).get("orderId")
check("C 前置：解除订单建单成功", code == 200 and bool(order_id),
      "HTTP {} {}".format(code, text[:200]))
if order_id:
    docs = [{"docType": t, "attachmentId": None, "deliveredTo": "DMS-DEV-01"}
            for t in ["RELEASE_CONSENT", "TRANSFER_CERTIFICATE",
                      "POWER_OF_ATTORNEY", "SEAL_CERTIFICATE"]]
    api("POST", "/api/release-orders/{}/documents".format(order_id), docs, token=staff_token)
    api("POST", "/api/release-orders/{}/issue".format(order_id), {}, token=staff_token)
    api("POST", "/api/release-orders/{}/external-submission".format(order_id),
        {"submissionRef": "SUB-" + TAG}, token=staff_token)

    receipt_ref = "LOCK-REF-" + TAG
    code, data, text = api("POST", "/api/release-orders/{}/receipts".format(order_id),
                           {"authority": "EXTERNAL_REGISTRY", "receiptRef": receipt_ref,
                            "verifiedBy": "admin", "rawRef": "raw-1-" + TAG}, token=staff_token)
    check("A40 首次外部回执登记成功（订单闭环）",
          code == 200 and (data or {}).get("status") == "CLOSED",
          "HTTP {} {}".format(code, text[:200]))
    code2, data2, text2 = api("POST", "/api/release-orders/{}/receipts".format(order_id),
                              {"authority": "EXTERNAL_REGISTRY", "receiptRef": receipt_ref,
                               "verifiedBy": "admin", "rawRef": "raw-2-" + TAG}, token=staff_token)
    check("A40 同一业务回执重复投递 -> 幂等返回（不报错）", code2 == 200,
          "HTTP {} {}".format(code2, text2[:200]))
    r_n = psql("SELECT count(*) FROM core.registration_receipts "
               "WHERE release_order_id={}".format(order_id))
    check("A40 库内回执行数 = 1（不重复落库）", r_n == "1", "rows={}".format(r_n))
    ev2 = count_event("TITLE_RELEASE_REGISTERED", str(order_id))
    check("A40 TITLE_RELEASE_REGISTERED 事件数 = 1（不重复发事件）", ev2 == 1,
          "events={}".format(ev2))

    # DMZ 入站：外部方换 nonce 重推同一业务回执
    def dmz_push(nonce_suffix, raw_ref):
        path = "/external/registry/receipts"
        body_text = json.dumps({"submissionRef": "SUB-" + TAG, "releaseOrderId": order_id,
                                 "receiptRef": receipt_ref, "rawRef": raw_ref})
        ts = str(int(time.time()))
        nonce = "{}-dmz-{}".format(TAG, nonce_suffix)
        canonical = NL.join(["POST", path, ts, nonce, "v1",
                             hashlib.sha256(body_text.encode()).hexdigest()])
        signature = base64.b64encode(
            hmac.new(PARTNER_REG_SECRET.encode(), canonical.encode(), hashlib.sha256).digest()).decode()
        return req("POST", DMZ + path,
                   {"Content-Type": "application/json", "X-Partner-Code": "REG-SIM-01",
                    "X-Nonce": nonce, "X-Timestamp": ts, "X-Key-Version": "v1",
                    "X-Signature": signature}, body_text.encode(), timeout=60)

    dcode, dtext = dmz_push("a", "raw-dmz-1-" + TAG)
    check("A40 DMZ 入站首次回执回调受理", dcode == 200, "HTTP {} {}".format(dcode, dtext[:200]))
    dcode2, dtext2 = dmz_push("b", "raw-dmz-2-" + TAG)
    check("A40 DMZ 换 nonce 重推同一回执 -> 幂等（200）", dcode2 == 200,
          "HTTP {} {}".format(dcode2, dtext2[:200]))
    r_n2 = psql("SELECT count(*) FROM core.registration_receipts "
                "WHERE release_order_id={}".format(order_id))
    check("A40 DMZ 重推后库内回执行数仍 = 1", r_n2 == "1", "rows={}".format(r_n2))

print("")
print("=" * 60)
print("锁专项冒烟：PASS {} / FAIL {} / SKIP {}".format(PASS, len(FAIL), len(SKIP)))
if FAIL:
    for name in FAIL:
        print("  FAIL  {}".format(name))
print("=" * 60)
sys.exit(1 if FAIL else 0)
