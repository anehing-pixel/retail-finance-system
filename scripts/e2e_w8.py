# -*- coding: utf-8 -*-
"""W8 一镜到底全链路 E2E（12 部署单元，全走网关 18081）。

链路：员工登录 → 客户注册（PII 加密落库）→ 身份核验 VERIFIED → 同意 grant →
申请提交（幂等键 + APPLICATION_SUBMITTED）→ 审查案件 → 征信查询（CIC 挡板）→
INQUIRY_DONE（事件驱动）→ 支付能力 PASS → APPROVED → 合同四步（DRAFT→…→EFFECTIVE，
含冷静期占位）→ 债权建立 → 还款计划 → schedule-due-rollup → 代付（G8 真实三门禁）→
还款核销（schedule_items 对拍）→ 全部结清 SETTLED → LOAN_SETTLED → title-release
EVENT 建单 → 证据链四件套 → 代录≠完成（MANUAL_RECORDED）→ 外部回执闭环 →
DPD 快照 job → recon 对拍 → 客户自助脱敏视图 → 数据面断言（outbox 无 PII / 结清流水 /
无 OPEN 差异）。

口径：断言风格与 smoke_w2.py 一致（check / SKIP + PASS/FAIL 汇总）；业务失败不退出，
尽量跑完独立链路。可重复执行（业务键用时间戳唯一化）。
"""
import io
import json
import os
import subprocess
import sys
import time
import traceback
import urllib.request
import urllib.error

# stdout 强制 utf-8（Windows 控制台/管道默认 cp936，遇异体字会 UnicodeEncodeError）
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# 禁用系统代理（本机 shell 注入 HTTP(S)_PROXY，直连必须绕开，否则 502）
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GATEWAY = os.environ.get("E2E_GATEWAY", "http://127.0.0.1:18081").rstrip("/")
DB_CONTAINER = os.environ.get("DB_CONTAINER", "retail-finance-db")
TS = int(time.time())
RUN_TAG = "e2e8-{}".format(TS)

PASS_COUNT = 0
FAIL = []
SKIP = []


def check(name, cond, detail=""):
    global PASS_COUNT
    if cond:
        PASS_COUNT += 1
        print("PASS  {}".format(name))
    else:
        FAIL.append(name)
        print("FAIL  {}  {}".format(name, detail))


def skip(name, reason):
    SKIP.append(name)
    print("SKIP  {}  {}".format(name, reason))


def req(method, url, headers=None, data=None, timeout=20):
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


def api(method, path, body=None, token=None, idem=None, headers=None):
    """调 API（path 以 http 开头则直连，否则走网关），返回 (code, data|None, 原始文本)。"""
    url = path if path.startswith("http") else GATEWAY + path
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    if idem:
        h["Idempotency-Key"] = idem
    for k, v in (headers or {}).items():
        h[k] = v
    payload = json.dumps(body).encode() if body is not None else None
    code, text = req(method, url, h, payload)
    obj = None
    try:
        obj = json.loads(text)
    except Exception:
        pass
    data = obj.get("data") if isinstance(obj, dict) else None
    return code, data, text


def psql(sql, timeout=20):
    out = subprocess.run(
        ["docker", "exec", DB_CONTAINER, "psql", "-U", "postgres", "-d", "retail_finance",
         "-tAc", sql], capture_output=True, text=True, timeout=timeout)
    if out.returncode != 0:
        raise RuntimeError("psql 失败: {} {}".format(out.stdout[:200], out.stderr[:200]))
    return out.stdout.strip()


def kv(key, value):
    """事件 payload 里 '"key":value' 的匹配串（数字不带引号，其余带引号）。"""
    if isinstance(value, int):
        return '"{}":{}'.format(key, value)
    return '"{}":"{}"'.format(key, value)


def wait_event(event_type, expect=None, timeout=30, interval=1.0):
    """轮询 core.outbox_events 等事件（payload 需同时包含 expect 各键值）；超时返回 None。"""
    expect = {k: str(v) for k, v in expect.items()}
    deadline = time.time() + timeout
    last_payload = ""
    while time.time() < deadline:
        try:
            rows = psql("SELECT event_id, payload FROM core.outbox_events "
                        "WHERE event_type = '{}' ORDER BY event_id DESC LIMIT 30".format(event_type))
        except Exception as e:
            last_payload = str(e)
            rows = ""
        for line in rows.splitlines():
            line = line.strip()
            if "|" not in line:
                continue
            _, payload = line.split("|", 1)
            last_payload = payload
            if all(v in payload for v in expect.values()):
                try:
                    return json.loads(payload)
                except Exception:
                    return {"raw": payload}
        time.sleep(interval)
    print("      [wait_event 超时] {} 最后状态: {}".format(event_type, last_payload[:300]))
    return None


def wait_until(fn, desc, timeout=30, interval=1.0):
    """通用轮询：fn() 返回真值即返回；超时返回 None 并打印最后状态。"""
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        try:
            value = fn()
            if value:
                return value
            last = str(value)
        except Exception as e:
            last = str(e)
        time.sleep(interval)
    print("      [wait_until 超时] {} 最后状态: {}".format(desc, last[:300]))
    return None


def require(ctx, key, step_name):
    if ctx.get(key) is None:
        skip(step_name, "前置步骤未产生 {}，无法继续该链路".format(key))
        return False
    return True


TODAY = time.strftime("%Y-%m-%d")
ctx = {}

print("=" * 68)
print("W8 一镜到底 E2E  run={}  gateway={}".format(RUN_TAG, GATEWAY))
print("=" * 68)

# ---- 步骤 0：网关健康 ----
code, data, text = api("GET", "/api/health")
check("S0 网关→retail-core 健康", code == 200 and data and data.get("status") == "UP",
      "HTTP {} {}".format(code, text[:120]))

# ---- 步骤 1：员工登录 ----
code, data, text = api("POST", "/api/auth/staff/login",
                       {"username": "admin", "password": "admin123"})
staff_token = (data or {}).get("token", "")
check("S1 员工登录 admin/admin123 → 员工 token",
      code == 200 and staff_token and (data or {}).get("subjectType") == "EMPLOYEE",
      "HTTP {} {}".format(code, text[:160]))
check("S1 token 携带全链路权限点",
      all(p in text for p in ["IDENTITY_VERIFY", "CONSENT_MAINTAIN", "APPLICATION_REVIEW",
                              "CREDIT_INQUIRY_EXEC", "UNDERWRITING_DECIDE", "CONTRACT_ISSUE",
                              "DISBURSEMENT_EXEC", "PAYMENT_ALLOCATE", "SETTLEMENT_CONFIRM",
                              "TITLE_REGISTER", "RELEASE_DOCUMENT_ISSUE", "RELEASE_REGISTER_CONFIRM",
                              "JOB_TRIGGER"]),
      text[:300])
ctx["staff_token"] = staff_token

# ---- 步骤 2：客户注册（PII 加密落库）----
CUSTOMER_NAME = "山田太郎"
CUSTOMER_PHONE = "090{:08d}".format(TS % 100000000)
try:
    code, data, text = api("POST", "/api/customers", {
        "externalRef": "EXT-" + RUN_TAG,
        "name": CUSTOMER_NAME,
        "nameKana": "ヤマダタロウ",
        "address": "東京都千代田区丸の内1-1-1",
        "birthDate": "1990-01-01",
        "phone": CUSTOMER_PHONE,
        "employer": "E2E株式会社",
        "annualIncome": "6000000",
    }, token=staff_token)
    customer_id = (data or {}).get("customerId")
    check("S2 客户注册返回 customerId", code == 200 and customer_id,
          "HTTP {} {}".format(code, text[:200]))
    ctx["customer_id"] = customer_id
    if customer_id:
        enc = psql("SELECT encrypted_name, name_hash FROM pii.customer_pii WHERE customer_id={}"
                   .format(customer_id)).split("|")
        enc_name = enc[0] if enc else ""
        name_hash = enc[1] if len(enc) > 1 else ""
        check("S2 PII 落库为密文（encrypted_name ≠ 明文且非空）",
              enc_name and CUSTOMER_NAME not in enc_name, "encrypted_name={}".format(enc_name[:60]))
        check("S2 name_hash 检索指纹存在（key_version 体系）", bool(name_hash), "name_hash 空")
except Exception:
    traceback.print_exc()
    check("S2 客户注册", False, "异常")

# ---- 步骤 3：身份核验 → VERIFIED ----
if require(ctx, "customer_id", "S3 身份核验"):
    try:
        code, data, text = api("POST", "/api/identity-verifications",
                               {"customerId": ctx["customer_id"]}, token=staff_token)
        verification_id = (data or {}).get("verificationId")
        check("S3 发起核验（method=MANUAL 挡板）", code == 200 and verification_id,
              "HTTP {} {}".format(code, text[:200]))
        code, data, text = api("POST", "/api/identity-verifications/{}/documents".format(verification_id),
                               {"documentType": "DRIVER_LICENSE", "attachmentId": None,
                                "expiryDate": "2030-12-31", "maskedNumber": "****1234"},
                               token=staff_token)
        check("S3 提交核验材料", code == 200, "HTTP {} {}".format(code, text[:200]))
        code, data, text = api("POST", "/api/identity-verifications/{}/start".format(verification_id),
                               {}, token=staff_token)
        check("S3 进入人工核验中", code == 200, "HTTP {} {}".format(code, text[:200]))
        code, data, text = api("POST", "/api/identity-verifications/{}/review".format(verification_id),
                               {"approve": True, "reviewerId": "admin", "note": "e2e"},
                               token=staff_token)
        check("S3 人工审核 approve", code == 200, "HTTP {} {}".format(code, text[:200]))
        code, data, text = api("GET", "/api/identity-verifications/{}".format(verification_id),
                               token=staff_token)
        check("S3 核验状态 → VERIFIED", code == 200 and (data or {}).get("status") == "VERIFIED",
              "HTTP {} {}".format(code, text[:200]))
        ctx["verification_id"] = verification_id
    except Exception:
        traceback.print_exc()
        check("S3 身份核验", False, "异常")

# ---- 步骤 4：同意 grant ----
if require(ctx, "customer_id", "S4 同意取得"):
    try:
        expires = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(TS + 90 * 86400))
        code, data, text = api("POST", "/api/consents", {
            "customerId": ctx["customer_id"], "purpose": "CREDIT_INQUIRY",
            "consentVersion": "v1", "channel": "PORTAL", "evidenceAttachmentId": None,
            "expiresAt": expires,
        }, token=staff_token)
        consent_id = (data or {}).get("consentId")
        check("S4 同意 grant 返回 consentId", code == 200 and consent_id,
              "HTTP {} {}".format(code, text[:200]))
        ctx["consent_id"] = consent_id
        code, data, text = api("GET", "/api/consents/validity?customerId={}&purpose=CREDIT_INQUIRY"
                               .format(ctx["customer_id"]), token=staff_token)
        check("S4 同意有效性查询 valid=true",
              code == 200 and (data or {}).get("valid") is True,
              "HTTP {} {}".format(code, text[:200]))
        ev = wait_event("CONSENT_GRANTED", {"consentId": consent_id})
        check("S4 outbox 发出 CONSENT_GRANTED", ev is not None, "30s 内未出现该事件")
    except Exception:
        traceback.print_exc()
        check("S4 同意取得", False, "异常")

# ---- 步骤 5：申请提交（幂等）----
CASH_PRICE = 1200000
ACCESSORIES = 30000
PRINCIPAL = CASH_PRICE + ACCESSORIES  # 1230000
VIN = "VIN-E2E8-{}".format(TS)
if require(ctx, "customer_id", "S5 申请提交"):
    try:
        app_body = {
            "customerId": ctx["customer_id"], "dealerPartnerId": None,
            "transactionScheme": "INSTALLMENT_CREDIT", "channel": "PORTAL",
            "consentId": ctx.get("consent_id"), "vin": VIN, "model": "E2E-TEST-MODEL",
            "cashPrice": CASH_PRICE, "accessoriesAmount": ACCESSORIES,
        }
        code, data, text = api("POST", "/api/applications", app_body,
                               token=staff_token, idem=RUN_TAG + "-app")
        application_id = (data or {}).get("applicationId")
        check("S5 申请提交 → SUBMITTED",
              code == 200 and application_id and (data or {}).get("status") == "SUBMITTED",
              "HTTP {} {}".format(code, text[:250]))
        ctx["application_id"] = application_id
        check("S5 生成申请编号 applicationNo",
              bool((data or {}).get("applicationNo")), text[:200])
        # 幂等重放：同键同指纹 → replayed=true
        code, data, text = api("POST", "/api/applications", app_body,
                               token=staff_token, idem=RUN_TAG + "-app")
        check("S5 幂等重放同键 → replayed=true",
              code == 200 and (data or {}).get("replayed") is True and
              (data or {}).get("applicationId") == application_id,
              "HTTP {} {}".format(code, text[:250]))
        ev = wait_event("APPLICATION_SUBMITTED", {"applicationId": application_id})
        check("S5 outbox 发出 APPLICATION_SUBMITTED", ev is not None, "30s 内未出现该事件")
    except Exception:
        traceback.print_exc()
        check("S5 申请提交", False, "异常")

# ---- 步骤 6：审查域（建案 → 征信 → INQUIRY_DONE → 评估 PASS → APPROVED）----
if require(ctx, "application_id", "S6 审查域") and require(ctx, "consent_id", "S6 审查域"):
    try:
        code, data, text = api("POST", "/api/underwriting/cases",
                               {"applicationId": ctx["application_id"],
                                "consentId": ctx["consent_id"]},
                               token=staff_token)
        case_id = (data or {}).get("caseId")
        check("S6 建审查案件（G3 同意门禁真实通过）→ PENDING",
              code == 200 and case_id and (data or {}).get("status") == "PENDING",
              "HTTP {} {}".format(code, text[:250]))
        ctx["case_id"] = case_id

        # G3 负例：不存在/已撤回的同意 → fail-closed
        code, data, text = api("POST", "/api/underwriting/cases",
                               {"applicationId": ctx["application_id"], "consentId": 999999999},
                               token=staff_token)
        check("S6 G3 负例：无效同意建案 → RF_UW_003 fail-closed",
              code in (409, 422) and "RF_UW_003" in text,
              "HTTP {} {}".format(code, text[:200]))

        code, data, text = api("POST", "/api/underwriting/cases/{}/start-inquiry".format(case_id),
                               {}, token=staff_token)
        check("S6 发起征信查询 → INQUIRY_IN_PROGRESS",
              code == 200 and (data or {}).get("status") == "INQUIRY_IN_PROGRESS",
              "HTTP {} {}".format(code, text[:250]))

        code, data, text = api("POST", "/api/credit-inquiries",
                               {"caseId": case_id, "consentId": ctx["consent_id"],
                                "bureau": "CIC", "purpose": "CREDIT_INQUIRY"},
                               token=staff_token)
        inquiry_id = (data or {}).get("inquiryId")
        check("S6 征信查询（CIC 挡板）→ COMPLETED + 留痕",
              code == 200 and inquiry_id and (data or {}).get("status") == "COMPLETED",
              "HTTP {} {}".format(code, text[:250]))
        if inquiry_id:
            code, data, text = api("GET", "/api/credit-inquiries/{}".format(inquiry_id),
                                   token=staff_token)
            check("S6 查询留痕 messageLogCount ≥ 1",
                  code == 200 and (data or {}).get("messageLogCount", 0) >= 1,
                  "HTTP {} {}".format(code, text[:200]))
        ev = wait_event("CREDIT_INQUIRY_COMPLETED", {"inquiryId": inquiry_id, "caseId": case_id})
        check("S6 outbox 发出 CREDIT_INQUIRY_COMPLETED", ev is not None, "30s 内未出现该事件")

        # 事件驱动推进：INQUIRY_IN_PROGRESS → INQUIRY_DONE（轮询案件状态）
        done = wait_until(
            lambda: (api("GET", "/api/underwriting/cases/{}".format(case_id),
                         token=staff_token)[1] or {}).get("status") == "INQUIRY_DONE" and True,
            "案件 INQUIRY_DONE")
        check("S6 消费事件推进 → INQUIRY_DONE", done is True, "状态未随事件推进")

        code, data, text = api("POST", "/api/underwriting/cases/{}/affordability".format(case_id),
                               {"scheme": "INSTALLMENT_CREDIT",
                                "annualIncomeEncRef": "INC-" + RUN_TAG,
                                "existingDebtTotal": 0, "thresholdAmount": 5000000,
                                "requestedAmount": PRINCIPAL},
                               token=staff_token)
        check("S6 支付能力评估 → PASS（C-03 挡板）",
              code == 200 and (data or {}).get("result") == "PASS",
              "HTTP {} {}".format(code, text[:250]))
        code, data, text = api("POST", "/api/underwriting/cases/{}/decision".format(case_id),
                               {"outcome": "APPROVED", "reasonCode": "E2E-W8"},
                               token=staff_token)
        check("S6 审查决定 → APPROVED（决定人=验签身份 admin）",
              code == 200 and (data or {}).get("status") == "APPROVED" and
              (data or {}).get("decidedBy") == "admin",
              "HTTP {} {}".format(code, text[:250]))
        ev = wait_event("UNDERWRITING_APPROVED", {"caseId": case_id})
        check("S6 outbox 发出 UNDERWRITING_APPROVED", ev is not None, "30s 内未出现该事件")
    except Exception:
        traceback.print_exc()
        check("S6 审查域", False, "异常")

# ---- 步骤 7：合同域（建合同 → 书面交付 → 签订 → 冷静期 → EFFECTIVE）----
if require(ctx, "application_id", "S7 合同域"):
    try:
        code, data, text = api("POST", "/api/contracts",
                               {"applicationId": ctx["application_id"],
                                "customerId": ctx["customer_id"],
                                "transactionScheme": "INSTALLMENT_CREDIT"},
                               token=staff_token)
        contract_id = (data or {}).get("contractId")
        check("S7 建合同 → DRAFT", code == 200 and contract_id and (data or {}).get("status") == "DRAFT",
              "HTTP {} {}".format(code, text[:250]))
        ctx["contract_id"] = contract_id

        code, data, text = api("POST", "/api/contracts/{}/documents".format(contract_id),
                               {"docType": "PRE_CONTRACT_STATEMENT", "templateVersion": "C08-1.0",
                                "deliveryMethod": "PAPER", "attachmentId": None},
                               token=staff_token)
        check("S7 交付留痕①法定说明书面", code == 200, "HTTP {} {}".format(code, text[:200]))
        code, data, text = api("POST", "/api/contracts/{}/documents".format(contract_id),
                               {"docType": "CONTRACT_DOCUMENT", "templateVersion": "C08-1.0",
                                "deliveryMethod": "PAPER", "attachmentId": None},
                               token=staff_token)
        check("S7 交付留痕②合同书面", code == 200, "HTTP {} {}".format(code, text[:200]))

        code, data, text = api("POST", "/api/contracts/{}/documents-delivered".format(contract_id),
                               {}, token=staff_token)
        check("S7 书面交付齐全确认 → DOCUMENTS_DELIVERED",
              code == 200 and (data or {}).get("status") == "DOCUMENTS_DELIVERED",
              "HTTP {} {}".format(code, text[:250]))

        code, data, text = api("POST", "/api/contracts/{}/sign".format(contract_id),
                               {}, token=staff_token)
        check("S7 签订 → COOLING_OFF（个品分期冷静期 8 日占位）",
              code == 200 and (data or {}).get("status") == "COOLING_OFF",
              "HTTP {} {}".format(code, text[:250]))
        ev = wait_event("CONTRACT_SIGNED", {"contractId": contract_id})
        check("S7 outbox 发出 CONTRACT_SIGNED", ev is not None, "30s 内未出现该事件")

        # 冷静期占位确认 → EFFECTIVE
        code, data, text = api("POST", "/api/contracts/{}/cooling-off/confirm".format(contract_id),
                               {}, token=staff_token)
        check("S7 冷静期确认 → EFFECTIVE",
              code == 200 and (data or {}).get("status") == "EFFECTIVE",
              "HTTP {} {}".format(code, text[:250]))
        code, data, text = api("GET", "/api/contracts/{}/disbursement-gate".format(contract_id),
                               token=staff_token)
        check("S7 合同门禁 provider 侧 allowed=true",
              code == 200 and (data or {}).get("allowed") is True,
              "HTTP {} {}".format(code, text[:200]))
    except Exception:
        traceback.print_exc()
        check("S7 合同域", False, "异常")

# ---- 步骤 8：账务（建债权 → 还款计划 → 代付三门禁 → ACTIVE）----
if require(ctx, "contract_id", "S8 账务") and require(ctx, "case_id", "S8 账务"):
    try:
        contract_id = ctx["contract_id"]
        # G8 负例（真实 HTTP 门禁 fail-closed）：不存在的合同 → CONTRACT 门禁拒绝
        code, data, text = api("POST", "/api/disbursements",
                               {"loanId": 1, "contractId": 999999999,
                                "underwritingCaseId": ctx["case_id"],
                                "payeeAccountRef": "ACCT-" + RUN_TAG},
                               token=staff_token, idem=RUN_TAG + "-gate-neg1")
        check("S8 G8 负例：合同不存在 → RF_LOAN_001 拒绝代付（真实调用合同门禁）",
              code >= 400 and "RF_LOAN_001" in text,
              "HTTP {} {}".format(code, text[:250]))
        # G8 负例：审查案件不存在 → UNDERWRITING 门禁拒绝
        code, data, text = api("POST", "/api/disbursements",
                               {"loanId": 1, "contractId": contract_id,
                                "underwritingCaseId": 999999999,
                                "payeeAccountRef": "ACCT-" + RUN_TAG},
                               token=staff_token, idem=RUN_TAG + "-gate-neg2")
        check("S8 G8 负例：审查案件不存在 → RF_LOAN_001 拒绝代付（真实调用审查门禁）",
              code >= 400 and "RF_LOAN_001" in text,
              "HTTP {} {}".format(code, text[:250]))
        # 幂等负例：缺 Idempotency-Key
        code, text = req("POST", GATEWAY + "/api/disbursements",
                         {"Content-Type": "application/json",
                          "Authorization": "Bearer " + staff_token},
                         json.dumps({"loanId": 1, "contractId": contract_id,
                                     "underwritingCaseId": ctx["case_id"],
                                     "payeeAccountRef": "ACCT-" + RUN_TAG}).encode())
        check("S8 幂等负例：缺 Idempotency-Key → 拒绝", code >= 400, "HTTP {} {}".format(code, text[:160]))

        code, data, text = api("POST", "/api/loans",
                               {"contractId": contract_id, "principal": PRINCIPAL, "feeAmount": 0},
                               token=staff_token)
        loan_id = (data or {}).get("loanId")
        check("S8 建债权 → PENDING_DISBURSEMENT",
              code == 200 and loan_id and (data or {}).get("status") == "PENDING_DISBURSEMENT",
              "HTTP {} {}".format(code, text[:250]))
        ctx["loan_id"] = loan_id

        code, data, text = api("POST", "/api/repayment-plans",
                               {"loanId": loan_id, "method": "EQUAL_PAYMENT",
                                "installmentCount": 3, "firstDueDate": TODAY,
                                "monthlyRateBp": 0, "bonusMonth": None},
                               token=staff_token)
        check("S8 还款计划 EQUAL_PAYMENT×3（0 利率）→ 总额=本金",
              code == 200 and (data or {}).get("totalAmountDue") == PRINCIPAL,
              "HTTP {} {}".format(code, text[:250]))

        # 三门禁全过：合同 EFFECTIVE + 审查 APPROVED（+ 同意有效已在 G3 验证）→ PAID
        code, data, text = api("POST", "/api/disbursements",
                               {"loanId": loan_id, "contractId": contract_id,
                                "underwritingCaseId": ctx["case_id"],
                                "payeeAccountRef": "DEALER-ACCT-" + RUN_TAG},
                               token=staff_token, idem=RUN_TAG + "-disb")
        check("S8 代付执行（G8 真实三门禁通过）→ PAID",
              code == 200 and (data or {}).get("status") == "PAID" and
              (data or {}).get("amount") == PRINCIPAL,
              "HTTP {} {}".format(code, text[:250]))
        disbursement_id = (data or {}).get("disbursementId")
        ctx["disbursement_id"] = disbursement_id
        check("S8 代付后债权 → ACTIVE",
              (data or {}).get("loanStatus") == "ACTIVE", text[:250])
        ev = wait_event("DISBURSEMENT_PAID", {"disbursementId": disbursement_id})
        check("S8 outbox 发出 DISBURSEMENT_PAID", ev is not None, "30s 内未出现该事件")

        # 代付幂等重放：同键同指纹 → 返回首次结果
        code, data, text = api("POST", "/api/disbursements",
                               {"loanId": loan_id, "contractId": contract_id,
                                "underwritingCaseId": ctx["case_id"],
                                "payeeAccountRef": "DEALER-ACCT-" + RUN_TAG},
                               token=staff_token, idem=RUN_TAG + "-disb")
        check("S8 代付幂等重放 → 同一 disbursementId",
              code == 200 and (data or {}).get("disbursementId") == disbursement_id,
              "HTTP {} {}".format(code, text[:250]))
    except Exception:
        traceback.print_exc()
        check("S8 账务", False, "异常")

# ---- 步骤 9：还款（schedule-due-rollup → 入金核销 → 对拍）----
if require(ctx, "loan_id", "S9 还款"):
    try:
        loan_id = ctx["loan_id"]
        # 到期批量：OPEN → DUE（真实 job，非手工改库）。当日只滚当日到期，先断言 dueCount≥1，
        # 再滚到末期到期日之后使 3 期全部 DUE（job 语义：due_date ≤ bizDate 的期次全部转 DUE）。
        code, data, text = api("POST", "/api/jobs/schedule-due-rollup", {"bizDate": TODAY},
                               token=staff_token)
        check("S9 schedule-due-rollup job 触发成功（当日到期滚转）",
              code == 200 and (data or {}).get("dueCount", 0) >= 1,
              "HTTP {} {} data={}".format(code, text[:200], json.dumps(data, ensure_ascii=False)[:200]))
        future = time.strftime("%Y-%m-%d", time.gmtime(TS + 120 * 86400))
        code, data, text = api("POST", "/api/jobs/schedule-due-rollup", {"bizDate": future},
                               token=staff_token)
        check("S9 schedule-due-rollup 滚至末期到期日后（3 期全部 DUE）",
              code == 200 and (data or {}).get("dueCount", 0) >= 2,
              "HTTP {} {} data={}".format(code, text[:200], json.dumps(data, ensure_ascii=False)[:200]))

        code, data, text = api("GET", "/api/repayment-plans?loanId={}".format(loan_id),
                               token=staff_token)
        items = (data or {}).get("items") or []
        # 滚至末期到期日后，过去到期日转 OVERDUE（同样可核销），故口径为「全部可核销」
        check("S9 计划 3 期且状态全部可核销（DUE/OVERDUE，到期批量生效）",
              code == 200 and len(items) == 3 and
              all(i.get("status") in ("DUE", "OVERDUE") for i in items),
              json.dumps(data, ensure_ascii=False)[:300])
        due_amounts = [i.get("amountDue", 0) for i in items]
        total_due = sum(due_amounts)
        check("S9 各期合计 = 本金 1230000", total_due == PRINCIPAL,
              "items={} total={}".format(due_amounts, total_due))

        # 逐期入金核销（Idempotency-Key 每笔唯一），核销后逐期断言状态流转
        paid_map = {}
        for idx, item in enumerate(items, start=1):
            amount = item.get("amountDue", 0)
            code, data, text = api("POST", "/api/payments",
                                   {"loanId": loan_id, "accountRef": "ACCT-" + RUN_TAG,
                                    "transactionId": "TX-{}-{}".format(RUN_TAG, idx),
                                    "amount": amount, "channel": "BANK_TRANSFER",
                                    "paymentRef": "PM-{}-{}".format(RUN_TAG, idx)},
                                   token=staff_token, idem="{}-pay{}".format(RUN_TAG, idx))
            check("S9 第{}期入金核销 {} 円 → ALLOCATED".format(idx, amount),
                  code == 200 and (data or {}).get("status") == "ALLOCATED" and
                  (data or {}).get("loanStatusAfter") == "ACTIVE",
                  "HTTP {} {}".format(code, text[:250]))
            paid_map[idx] = (data or {}).get("paymentId")
            code, data, text = api("GET", "/api/repayment-plans?loanId={}".format(loan_id),
                                   token=staff_token)
            cur = ((data or {}).get("items") or [])[idx - 1]
            check("S9 第{}期 schedule_item → PAID（核销对拍）".format(idx),
                  cur.get("status") == "PAID" and cur.get("paidAmount") == amount,
                  json.dumps(cur, ensure_ascii=False)[:200])
            code, data, text = api("GET", "/api/loans/{}/balance".format(loan_id),
                                   token=staff_token)
            expect_balance = total_due - sum(due_amounts[:idx])
            check("S9 第{}期后余额对拍 balance={}".format(idx, expect_balance),
                  code == 200 and (data or {}).get("balance") == expect_balance,
                  "HTTP {} {}".format(code, text[:200]))
        ev = wait_event("REPAYMENT_RECEIVED", {"paymentRef": "PM-{}-1".format(RUN_TAG)})
        check("S9 outbox 发出 REPAYMENT_RECEIVED", ev is not None, "30s 内未出现该事件")
        # 幂等负例：同 transactionId 重复入金（uk_payment_account_txn 去重）
        code, data, text = api("POST", "/api/payments",
                               {"loanId": loan_id, "accountRef": "ACCT-" + RUN_TAG,
                                "transactionId": "TX-{}-1".format(RUN_TAG),
                                "amount": 100, "channel": "BANK_TRANSFER"},
                               token=staff_token, idem=RUN_TAG + "-dup")
        check("S9 幂等负例：同 accountRef+transactionId 重复入金 → 拒绝", code >= 400,
              "HTTP {} {}".format(code, text[:200]))
    except Exception:
        traceback.print_exc()
        check("S9 还款", False, "异常")

# ---- 步骤 10：全部结清 → LOAN_SETTLED ----
if require(ctx, "loan_id", "S10 结清"):
    try:
        loan_id = ctx["loan_id"]
        code, data, text = api("GET", "/api/loans/{}/settlement-quote".format(loan_id),
                               token=staff_token)
        check("S10 结清试算 quote 可用", code == 200, "HTTP {} {}".format(code, text[:200]))

        code, data, text = api("POST", "/api/loans/{}/settle".format(loan_id),
                               {"type": "NORMAL", "waiverAmount": 0, "settlementFee": 0},
                               token=staff_token, idem=RUN_TAG + "-settle")
        check("S10 全部结清（NORMAL）→ SETTLED 且 quote=0（余额已精确归零，无结清入金）",
              code == 200 and (data or {}).get("status") == "SETTLED" and
              (data or {}).get("settlementType") == "NORMAL" and
              (data or {}).get("quote") == 0,
              "HTTP {} {}".format(code, text[:250]))
        code, data, text = api("GET", "/api/loans/{}".format(loan_id), token=staff_token)
        check("S10 债权终态 SETTLED + balance=0",
              code == 200 and (data or {}).get("status") == "SETTLED" and
              (data or {}).get("balance") == 0,
              "HTTP {} {}".format(code, text[:250]))
        ev = wait_event("LOAN_SETTLED", {"loanId": loan_id})
        check("S10 outbox 发出 LOAN_SETTLED", ev is not None, "30s 内未出现该事件")
        # 结清留痕（口径偏差 F3：settlement_adjustments / PAY-STL 结清入金仅 EARLY 落行；
        # NORMAL 以 loans.settlement_type + LOAN_SETTLED 事件为锚，详见报告）
        stl = psql("SELECT settlement_type || '|' || COALESCE(settled_at::text, '') "
                   "FROM core.loans WHERE loan_id={}".format(loan_id))
        check("S10 core.loans 结清留痕 settlement_type=NORMAL + settled_at",
              stl.startswith("NORMAL|") and len(stl) > 8, "value={}".format(stl))
    except Exception:
        traceback.print_exc()
        check("S10 结清", False, "异常")

# ---- 步骤 11：所有权保留 + 解押（事件建单 → 四件套 → 代录≠完成 → 外部回执闭环）----
if require(ctx, "loan_id", "S11 解押") and require(ctx, "contract_id", "S11 解押"):
    try:
        loan_id = ctx["loan_id"]
        # S9 保留登记（代付交付后登记）
        code, data, text = api("POST", "/api/title-retentions",
                               {"loanId": loan_id, "vin": VIN, "contractualOnly": False},
                               token=staff_token)
        title_id = (data or {}).get("titleId")
        check("S11 所有权保留登记 → REGISTERED",
              code == 200 and title_id and (data or {}).get("status") == "REGISTERED",
              "HTTP {} {}".format(code, text[:250]))
        ctx["title_id"] = title_id
        ev = wait_event("TITLE_RETENTION_REGISTERED", {"titleId": title_id})
        check("S11 outbox 发出 TITLE_RETENTION_REGISTERED", ev is not None, "30s 内未出现该事件")

        # LOAN_SETTLED 消费 → 事件驱动自动建单（source=EVENT）
        order_id = wait_until(
            lambda: psql("SELECT release_order_id FROM core.release_orders "
                         "WHERE loan_id={} AND source='EVENT'".format(loan_id)) or None,
            "EVENT 来源解除订单", timeout=40)
        check("S11 LOAN_SETTLED 消费自动建解除订单（source=EVENT）",
              bool(order_id), "40s 内 core.release_orders 无 source=EVENT 行")
        ctx["release_order_id"] = order_id

        if order_id:
            oid = str(order_id)
            # 证据链：登记解除四件套（齐套自动 → DOCUMENTS_PREPARED）
            docs = [{"docType": t, "attachmentId": None, "deliveredTo": "DMS-DEV-01"}
                    for t in ["RELEASE_CONSENT", "TRANSFER_CERTIFICATE",
                              "POWER_OF_ATTORNEY", "SEAL_CERTIFICATE"]]
            code, data, text = api("POST", "/api/release-orders/{}/documents".format(oid), docs,
                                   token=staff_token)
            check("S11 登记解除四件套 → DOCUMENTS_PREPARED",
                  code == 200 and (data or {}).get("status") == "DOCUMENTS_PREPARED",
                  "HTTP {} {}".format(code, text[:250]))
            code, data, text = api("POST", "/api/release-orders/{}/issue".format(oid), {},
                                   token=staff_token)
            check("S11 発行文件并交付 → DOCUMENTS_ISSUED（文件出具≠解押完成）",
                  code == 200 and (data or {}).get("status") == "DOCUMENTS_ISSUED",
                  "HTTP {} {}".format(code, text[:250]))
            ev = wait_event("RELEASE_DOCUMENTS_ISSUED", {"releaseOrderId": int(oid)})
            check("S11 outbox 发出 RELEASE_DOCUMENTS_ISSUED", ev is not None, "30s 内未出现该事件")

            code, data, text = api("POST", "/api/release-orders/{}/external-submission".format(oid),
                                   {"submissionRef": "SUB-" + RUN_TAG}, token=staff_token)
            check("S11 外部提交登记 → EXTERNAL_SUBMITTED（证据链⑥）",
                  code == 200 and (data or {}).get("status") == "EXTERNAL_SUBMITTED",
                  "HTTP {} {}".format(code, text[:250]))

            # 代录≠完成：INTERNAL_WITNESS 只落中间态
            code, data, text = api("POST", "/api/release-orders/{}/receipts".format(oid),
                                   {"authority": "INTERNAL_WITNESS", "receiptRef": "WIT-" + RUN_TAG,
                                    "verifiedBy": "admin", "rawRef": "raw-" + RUN_TAG},
                                   token=staff_token)
            check("S11 代录回执 → MANUAL_RECORDED 且 releaseCompleted=false",
                  code == 200 and (data or {}).get("status") == "MANUAL_RECORDED" and
                  (data or {}).get("releaseCompleted") is False,
                  "HTTP {} {}".format(code, text[:250]))
            code, data, text = api("GET", "/api/title-retentions?loanId={}".format(loan_id),
                                   token=staff_token)
            check("S11 代录后保留态仍 REGISTERED（代录≠解押完成）",
                  code == 200 and (data or {}).get("status") == "REGISTERED",
                  "HTTP {} {}".format(code, text[:250]))
            # X-10 负例：经销商自证不得采信
            code, data, text = api("POST", "/api/release-orders/{}/receipts".format(oid),
                                   {"authority": "PARTNER_SELF_REPORT", "receiptRef": "PSR-" + RUN_TAG,
                                    "verifiedBy": "admin", "rawRef": "raw-" + RUN_TAG},
                                   token=staff_token)
            check("S11 X-10 负例：PARTNER_SELF_REPORT → RF_TITLE_002 拒绝采信",
                  code >= 400 and "RF_TITLE_002" in text,
                  "HTTP {} {}".format(code, text[:250]))

            # 外部登记回执（唯一权威）→ TITLE_RELEASE_REGISTERED 闭环
            code, data, text = api("POST", "/api/release-orders/{}/receipts".format(oid),
                                   {"authority": "EXTERNAL_REGISTRY", "receiptRef": "REG-" + RUN_TAG,
                                    "verifiedBy": "admin", "rawRef": "raw-ext-" + RUN_TAG},
                                   token=staff_token)
            check("S11 外部登记回执 → 订单 CLOSED（RELEASE_REGISTERED → CLOSED）",
                  code == 200 and (data or {}).get("status") == "CLOSED" and
                  (data or {}).get("releaseCompleted") is True,
                  "HTTP {} {}".format(code, text[:250]))
            code, data, text = api("GET", "/api/title-retentions?loanId={}".format(loan_id),
                                   token=staff_token)
            check("S11 保留状态随外部回执闭环 → CLOSED",
                  code == 200 and (data or {}).get("status") == "CLOSED",
                  "HTTP {} {}".format(code, text[:250]))
            code, data, text = api("GET", "/api/release-orders/{}/evidence-chain".format(oid),
                                   token=staff_token)
            chain = (data or {}).get("items") or []
            check("S11 证据链八条全部闭环 allClosed=true",
                  code == 200 and (data or {}).get("allClosed") is True and len(chain) == 8,
                  "HTTP {} {}".format(code, text[:300]))
            ev = wait_event("TITLE_RELEASE_REGISTERED", {"releaseOrderId": int(oid)})
            check("S11 outbox 发出 TITLE_RELEASE_REGISTERED（INV-3：必须有外部回执）",
                  ev is not None, "30s 内未出现该事件")
    except Exception:
        traceback.print_exc()
        check("S11 解押", False, "异常")

# ---- 步骤 12：贷后（DPD 快照 + recon 对拍）----
# 口径偏差（见报告）：/api/jobs/dpd-snapshot、/api/dpd-snapshots 未在网关路由段内
# （网关 servicing 段仅 /api/collection-cases/**），E2E 按服务直连端口补验。
SERVICING = "http://127.0.0.1:8098"
try:
    code, data, text = api("POST", SERVICING + "/api/jobs/dpd-snapshot", {"bizDate": TODAY},
                           token=staff_token)
    check("S12 DPD 快照 job 触发成功（返回 batch 摘要）",
          code == 200 and (data or {}).get("jobName") == "DPD_SNAPSHOT",
          "HTTP {} {}".format(code, text[:200]))
    code, data, text = api("GET", SERVICING + "/api/dpd-snapshots?loanId={}".format(ctx.get("loan_id", 0)),
                           token=staff_token)
    snaps = data if isinstance(data, list) else None
    check("S12 DPD 快照查询接口可用（列表形状）", code == 200 and isinstance(snaps, list),
          "HTTP {} {}".format(code, text[:200]))
    code, data, text = api("POST", "/api/recon/run", {"bizDate": TODAY}, token=staff_token)
    check("S12 recon 对拍 run 执行（INV-1~5）",
          code == 200 and (data or {}).get("jobName") == "RECON_INV",
          "HTTP {} {}".format(code, text[:250]))
    code, data, text = api("GET", "/api/recon/runs", token=staff_token)
    runs = data if isinstance(data, list) else []
    check("S12 batch_runs 留痕：DPD_SNAPSHOT 与 RECON_INV 各有 SUCCESS 行",
          any(r.get("jobName") == "DPD_SNAPSHOT" and r.get("status") == "SUCCESS" for r in runs)
          and any(r.get("jobName") == "RECON_INV" and r.get("status") == "SUCCESS"
                  and r.get("bizDate") == TODAY for r in runs),
          json.dumps(runs, ensure_ascii=False)[:300])
    if ctx.get("loan_id"):
        code, data, text = api("GET", "/api/recon/diffs?status=OPEN", token=staff_token)
        opens = data if isinstance(data, list) else []
        mine = [d for d in opens if str(d.get("entityId")) == str(ctx["loan_id"])]
        check("S12 recon 无本债权的 OPEN 差异", len(mine) == 0,
              json.dumps(mine, ensure_ascii=False)[:300])
        check("S12 recon 全局 OPEN 差异计数（存在则需解释，仅提示）", True,
              "openDiffs={}".format(len(opens)))
    code, data, text = api("GET", "/api/collection-cases", token=staff_token)
    check("S12 催收案件清单接口可用（贷后域在线）", code == 200 and isinstance(data, list),
          "HTTP {} {}".format(code, text[:160]))
except Exception:
    traceback.print_exc()
    check("S12 贷后", False, "异常")

# ---- 步骤 13：客户自助（开发桥登录 + 脱敏视图）----
# 口径偏差（见报告）：/api/customer-self/** 未在网关路由段内，E2E 按直连端口补验。
CUSTOMER_SVC = "http://127.0.0.1:8092"
if require(ctx, "customer_id", "S13 客户自助"):
    try:
        cid = ctx["customer_id"]
        code, data, text = api("POST", "/api/auth/customer/login", {"username": str(cid)})
        cust_token = (data or {}).get("token", "")
        check("S13 客户开发桥登录（subject=CUSTOMER）",
              code == 200 and cust_token and (data or {}).get("subjectType") == "CUSTOMER",
              "HTTP {} {}".format(code, text[:200]))
        code, data, text = api("GET", CUSTOMER_SVC + "/api/customer-self/me", token=cust_token)
        pii = (data or {}).get("pii") or {}
        masked_name = pii.get("name", "")
        masked_phone = pii.get("phone", "")
        check("S13 /me 返回本人视图（customerId 自证）",
              code == 200 and (data or {}).get("customerId") == cid,
              "HTTP {} {}".format(code, text[:250]))
        check("S13 姓名脱敏：只显姓「山」+ 打码",
              masked_name == "山***" and masked_name != CUSTOMER_NAME,
              "masked_name={}".format(masked_name))
        check("S13 电话脱敏：末 4 位可辨、其余打码",
              masked_phone.endswith(CUSTOMER_PHONE[-4:]) and masked_phone.startswith("*"),
              "masked_phone={} orig_last4={}".format(masked_phone, CUSTOMER_PHONE[-4:]))
        check("S13 脱敏视图不泄漏明文姓名/电话",
              CUSTOMER_NAME not in text and CUSTOMER_PHONE not in text, text[:300])
        # 主体隔离负例：员工 token 打客户自助端点 → 拒绝
        code, data, text = api("GET", CUSTOMER_SVC + "/api/customer-self/me",
                               token=ctx.get("staff_token"))
        check("S13 主体隔离：员工 token 访问 /api/customer-self/me → 拒绝", code >= 400,
              "HTTP {} {}".format(code, text[:160]))
    except Exception:
        traceback.print_exc()
        check("S13 客户自助", False, "异常")

# ---- 步骤 14：数据面断言（outbox 事件全景 / PII 不泄漏 / 对拍口径）----
try:
    expected_events = {
        "IDENTITY_VERIFIED": {"verificationId": ctx.get("verification_id")},
        "CONSENT_GRANTED": {"consentId": ctx.get("consent_id")},
        "APPLICATION_SUBMITTED": {"applicationId": ctx.get("application_id")},
        "CREDIT_INQUIRY_COMPLETED": {"inquiryId": None},
        "UNDERWRITING_APPROVED": {"caseId": ctx.get("case_id")},
        "CONTRACT_SIGNED": {"contractId": ctx.get("contract_id")},
        "DISBURSEMENT_PAID": {"disbursementId": ctx.get("disbursement_id")},
        "REPAYMENT_RECEIVED": {"paymentRef": "PM-{}-3".format(RUN_TAG)},
        "LOAN_SETTLED": {"loanId": ctx.get("loan_id")},
        "TITLE_RETENTION_REGISTERED": {"titleId": ctx.get("title_id")},
        "RELEASE_DOCUMENTS_ISSUED": None,
        "TITLE_RELEASE_REGISTERED": None,
    }
    missing = []
    for etype, expect in expected_events.items():
        if expect is None:
            expect = {}
        expect = {k: v for k, v in expect.items() if v is not None}
        if wait_event(etype, expect, timeout=15) is None:
            missing.append(etype)
    check("S14 outbox 六类（含全链 12 类）事件齐全", len(missing) == 0,
          "缺失: {}".format(missing))

    # PII 不进 outbox：取本次相关事件 payload 全文，断言不含明文姓名/电话/地址
    payload_blob = psql("SELECT string_agg(payload, ' ') FROM core.outbox_events "
                        "WHERE event_type IN ('APPLICATION_SUBMITTED','UNDERWRITING_APPROVED',"
                        "'CONTRACT_SIGNED','DISBURSEMENT_PAID','LOAN_SETTLED',"
                        "'TITLE_RELEASE_REGISTERED','CREDIT_INQUIRY_COMPLETED')")
    leak = [s for s in [CUSTOMER_NAME, CUSTOMER_PHONE, "丸の内1-1-1"] if s in payload_blob]
    check("S14 outbox payload 无 PII 键/明文（INV-4）", not leak,
          "泄漏: {}".format(leak))

    # 结清流水 + 幂等留痕 + 消费留痕
    if ctx.get("loan_id"):
        ev = wait_event("LOAN_SETTLED", {"loanId": ctx["loan_id"], "status": "SETTLED"}, timeout=15)
        check("S14 LOAN_SETTLED 事件 payload 携带终态（status=SETTLED）", ev is not None,
              "15s 内未匹配到 payload")
        alloc = psql("SELECT count(*) FROM core.payment_allocations WHERE loan_id={}"
                     .format(ctx["loan_id"]))
        check("S14 正常还款 allocation 留痕 = 3 期", alloc == "3", "count={}".format(alloc))
    consumed = psql("SELECT count(*) FROM core.event_consumptions WHERE status='DONE' "
                    "AND consumer IN ('underwriting-service','title-release-service')")
    check("S14 消费侧 event_consumptions DONE 留痕 ≥ 2", int(consumed or 0) >= 2,
          "count={}".format(consumed))
    if ctx.get("customer_id"):
        logs = psql("SELECT count(*) FROM audit.pii_access_logs WHERE customer_id={} "
                    "AND purpose='CUSTOMER_SELF_VIEW'".format(ctx["customer_id"]))
        check("S14 客户自助访问留痕 pii_access_logs ≥ 1", int(logs or 0) >= 1,
              "count={}".format(logs))
except Exception:
    traceback.print_exc()
    check("S14 数据面断言", False, "异常")

# ---- 汇总 ----
print("")
print("=" * 68)
print("E2E 结果：PASS {} / FAIL {} / SKIP {}".format(PASS_COUNT, len(FAIL), len(SKIP)))
if SKIP:
    print("跳过项：")
    for s in SKIP:
        print("  - " + s)
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
    raise SystemExit(1)
print("全部通过 ✅")
