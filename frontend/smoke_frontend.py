# -*- coding: utf-8 -*-
"""前端页面级冒烟（本地验证用）。

验证对象是「页面发出的每一个请求」——全部经前端同源代理（18082）走真实链路：
  静态页可用 → /gw 网关段（S1 登录…S10 结清、S11 解押闭环）→ /svc 直连段（S12 DPD）
  → /cust 直连段（S13 客户自助脱敏 + 主体隔离负例）。
与 scripts/e2e_w8.py 的差异：不做 psql 数据面断言（E2E 已覆盖），解除建单改用
GET /api/release-orders?loanId= 只读端点轮询。

用法：栈在跑的前提下，python frontend/smoke_frontend.py   （EXIT 0 = 页面链路全通）
"""
import io
import json
import sys
import time
import urllib.error
import urllib.request

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

FE = "http://127.0.0.1:18082"
TS = int(time.time())
RUN = "fesm-{}".format(TS)
TODAY = time.strftime("%Y-%m-%d")

PASS = 0
FAIL = []


def check(name, cond, detail=""):
    global PASS
    if cond:
        PASS += 1
        print("PASS  {}".format(name))
    else:
        FAIL.append(name)
        print("FAIL  {}  {}".format(name, detail))


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


def api(method, path, body=None, token=None, idem=None):
    h = {"Content-Type": "application/json"}
    if token:
        h["Authorization"] = "Bearer " + token
    if idem:
        h["Idempotency-Key"] = idem
    payload = json.dumps(body).encode() if body is not None else None
    code, text = req(method, FE + path, h, payload)
    try:
        data = (json.loads(text) or {}).get("data")
    except Exception:
        data = None
    return code, data, text


def wait_until(fn, desc, timeout=45):
    deadline = time.time() + timeout
    while time.time() < deadline:
        v = fn()
        if v:
            return v
        time.sleep(1.0)
    print("      [超时] {}".format(desc))
    return None


print("=" * 66)
print("前端页面级冒烟  run={}  fe={}".format(RUN, FE))
print("=" * 66)

# ---- F0 静态页 ----
code, text = req("GET", FE + "/")
check("F0 index.html 200 且含控制台标题", code == 200 and "运营控制台" in text,
      "HTTP {} {}".format(code, text[:120]))
code, text = req("GET", FE + "/app.js")
check("F0 app.js 200 且含关键步骤函数", code == 200 and "s11All" in text and "runAll" in text,
      "HTTP {} {}".format(code, text[:120]))
code, text = req("GET", FE + "/gw/api/health")
check("F0 代理 /gw → 网关健康 UP", code == 200 and '"status":"UP"' in text,
      "HTTP {} {}".format(code, text[:120]))

# ---- F1 员工登录 ----
code, data, text = api("POST", "/gw/api/auth/staff/login",
                       {"username": "admin", "password": "admin123"})
T = (data or {}).get("token", "")
check("F1 员工登录（经代理）", code == 200 and T and (data or {}).get("subjectType") == "EMPLOYEE",
      "HTTP {} {}".format(code, text[:160]))

# ---- F2 客户注册 ----
PHONE = "090{:08d}".format(TS % 100000000)
code, data, text = api("POST", "/gw/api/customers", {
    "externalRef": "EXT-" + RUN, "name": "山田太郎", "nameKana": "ヤマダタロウ",
    "address": "東京都千代田区丸の内1-1-1", "birthDate": "1990-01-01",
    "phone": PHONE, "employer": "フロント株式会社", "annualIncome": "6000000",
}, token=T)
CID = (data or {}).get("customerId")
check("F2 客户注册 customerId", code == 200 and CID, "HTTP {} {}".format(code, text[:200]))

# ---- F3 身份核验 ----
code, data, text = api("POST", "/gw/api/identity-verifications", {"customerId": CID}, token=T)
VID = (data or {}).get("verificationId")
check("F3 发起核验", code == 200 and VID, "HTTP {} {}".format(code, text[:200]))
api("POST", "/gw/api/identity-verifications/{}/documents".format(VID),
    {"documentType": "DRIVER_LICENSE", "attachmentId": None, "expiryDate": "2030-12-31",
     "maskedNumber": "****1234"}, token=T)
api("POST", "/gw/api/identity-verifications/{}/start".format(VID), {}, token=T)
api("POST", "/gw/api/identity-verifications/{}/review".format(VID),
    {"approve": True, "reviewerId": "admin", "note": "fesm"}, token=T)
code, data, text = api("GET", "/gw/api/identity-verifications/{}".format(VID), token=T)
check("F3 核验 → VERIFIED", code == 200 and (data or {}).get("status") == "VERIFIED",
      "HTTP {} {}".format(code, text[:200]))

# ---- F4 同意 ----
EXPIRES = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(TS + 90 * 86400))
code, data, text = api("POST", "/gw/api/consents",
                       {"customerId": CID, "purpose": "CREDIT_INQUIRY", "consentVersion": "v1",
                        "channel": "PORTAL", "evidenceAttachmentId": None, "expiresAt": EXPIRES},
                       token=T)
CONSENT = (data or {}).get("consentId")
check("F4 同意 grant consentId", code == 200 and CONSENT, "HTTP {} {}".format(code, text[:200]))
code, data, text = api("GET", "/gw/api/consents/validity?customerId={}&purpose=CREDIT_INQUIRY"
                       .format(CID), token=T)
check("F4 同意有效性 valid=true", code == 200 and (data or {}).get("valid") is True,
      "HTTP {} {}".format(code, text[:160]))

# ---- F5 申请 + 幂等 ----
CASH, ACC = 1200000, 30000
PRINCIPAL = CASH + ACC
VIN = "VIN-FESM-{}".format(TS)
APP_BODY = {"customerId": CID, "dealerPartnerId": None, "transactionScheme": "INSTALLMENT_CREDIT",
            "channel": "PORTAL", "consentId": CONSENT, "vin": VIN,
            "model": "FESM-MODEL", "cashPrice": CASH, "accessoriesAmount": ACC}
code, data, text = api("POST", "/gw/api/applications", APP_BODY, token=T, idem=RUN + "-app")
APP = (data or {}).get("applicationId")
check("F5 申请提交 SUBMITTED", code == 200 and APP and (data or {}).get("status") == "SUBMITTED",
      "HTTP {} {}".format(code, text[:250]))
code, data, text = api("POST", "/gw/api/applications", APP_BODY, token=T, idem=RUN + "-app")
check("F5 幂等重放 replayed=true", code == 200 and (data or {}).get("replayed") is True
      and (data or {}).get("applicationId") == APP, "HTTP {} {}".format(code, text[:200]))

# ---- F6 审查 ----
code, data, text = api("POST", "/gw/api/underwriting/cases",
                       {"applicationId": APP, "consentId": CONSENT}, token=T)
CASE = (data or {}).get("caseId")
check("F6 建审查案件", code == 200 and CASE and (data or {}).get("status") == "PENDING",
      "HTTP {} {}".format(code, text[:250]))
code, data, text = api("POST", "/gw/api/underwriting/cases",
                       {"applicationId": APP, "consentId": 999999999}, token=T)
check("F6 G3 负例：无效同意 → RF_UW_003 fail-closed",
      code in (409, 422) and "RF_UW_003" in text, "HTTP {} {}".format(code, text[:160]))
api("POST", "/gw/api/underwriting/cases/{}/start-inquiry".format(CASE), {}, token=T)
code, data, text = api("POST", "/gw/api/credit-inquiries",
                       {"caseId": CASE, "consentId": CONSENT, "bureau": "CIC",
                        "purpose": "CREDIT_INQUIRY"}, token=T)
check("F6 征信查询（CIC 挡板）COMPLETED", code == 200 and (data or {}).get("status") == "COMPLETED",
      "HTTP {} {}".format(code, text[:250]))
done = wait_until(lambda: (lambda r: (r[1] or {}).get("status") == "INQUIRY_DONE")(
    api("GET", "/gw/api/underwriting/cases/{}".format(CASE), token=T)), "案件 INQUIRY_DONE")
check("F6 事件推进 → INQUIRY_DONE", done is True, "状态未随事件推进")
code, data, text = api("POST", "/gw/api/underwriting/cases/{}/affordability".format(CASE),
                       {"scheme": "INSTALLMENT_CREDIT", "annualIncomeEncRef": "INC-" + RUN,
                        "existingDebtTotal": 0, "thresholdAmount": 5000000,
                        "requestedAmount": PRINCIPAL}, token=T)
check("F6 支付能力 PASS", code == 200 and (data or {}).get("result") == "PASS",
      "HTTP {} {}".format(code, text[:200]))
code, data, text = api("POST", "/gw/api/underwriting/cases/{}/decision".format(CASE),
                       {"outcome": "APPROVED", "reasonCode": "FESM"}, token=T)
check("F6 决定 APPROVED + decidedBy=admin",
      code == 200 and (data or {}).get("status") == "APPROVED"
      and (data or {}).get("decidedBy") == "admin", "HTTP {} {}".format(code, text[:200]))

# ---- F7 合同 ----
code, data, text = api("POST", "/gw/api/contracts",
                       {"applicationId": APP, "customerId": CID,
                        "transactionScheme": "INSTALLMENT_CREDIT"}, token=T)
CTR = (data or {}).get("contractId")
check("F7 建合同 DRAFT", code == 200 and CTR and (data or {}).get("status") == "DRAFT",
      "HTTP {} {}".format(code, text[:250]))
for t in ["PRE_CONTRACT_STATEMENT", "CONTRACT_DOCUMENT"]:
    api("POST", "/gw/api/contracts/{}/documents".format(CTR),
        {"docType": t, "templateVersion": "C08-1.0", "deliveryMethod": "PAPER",
         "attachmentId": None}, token=T)
code, data, text = api("POST", "/gw/api/contracts/{}/documents-delivered".format(CTR), {}, token=T)
check("F7 书面交付齐全确认", code == 200 and (data or {}).get("status") == "DOCUMENTS_DELIVERED",
      "HTTP {} {}".format(code, text[:200]))
code, data, text = api("POST", "/gw/api/contracts/{}/sign".format(CTR), {}, token=T)
check("F7 签订 → COOLING_OFF", code == 200 and (data or {}).get("status") == "COOLING_OFF",
      "HTTP {} {}".format(code, text[:200]))
code, data, text = api("POST", "/gw/api/contracts/{}/cooling-off/confirm".format(CTR), {}, token=T)
check("F7 冷静期确认 → EFFECTIVE", code == 200 and (data or {}).get("status") == "EFFECTIVE",
      "HTTP {} {}".format(code, text[:200]))
code, data, text = api("GET", "/gw/api/contracts/{}/disbursement-gate".format(CTR), token=T)
check("F7 代付门禁 allowed=true", code == 200 and (data or {}).get("allowed") is True,
      "HTTP {} {}".format(code, text[:160]))

# ---- F8 账务 ----
code, data, text = api("POST", "/gw/api/loans",
                       {"contractId": CTR, "principal": PRINCIPAL, "feeAmount": 0}, token=T)
LOAN = (data or {}).get("loanId")
check("F8 建债权 PENDING_DISBURSEMENT",
      code == 200 and LOAN and (data or {}).get("status") == "PENDING_DISBURSEMENT",
      "HTTP {} {}".format(code, text[:250]))
code, data, text = api("POST", "/gw/api/repayment-plans",
                       {"loanId": LOAN, "method": "EQUAL_PAYMENT", "installmentCount": 3,
                        "firstDueDate": TODAY, "monthlyRateBp": 0, "bonusMonth: ": None},
                       token=T)
check("F8 还款计划总额=本金", code == 200 and (data or {}).get("totalAmountDue") == PRINCIPAL,
      "HTTP {} {}".format(code, text[:250]))
code, data, text = api("POST", "/gw/api/disbursements",
                       {"loanId": LOAN, "contractId": CTR, "underwritingCaseId": CASE,
                        "payeeAccountRef": "DEALER-ACCT-" + RUN}, token=T, idem=RUN + "-disb")
DISB = (data or {}).get("disbursementId")
check("F8 三门禁代付 → PAID + ACTIVE",
      code == 200 and (data or {}).get("status") == "PAID"
      and (data or {}).get("loanStatus") == "ACTIVE", "HTTP {} {}".format(code, text[:250]))
code, data, text = api("POST", "/gw/api/disbursements",
                       {"loanId": 1, "contractId": 999999999, "underwritingCaseId": CASE,
                        "payeeAccountRef": "NEG-" + RUN}, token=T, idem=RUN + "-neg")
check("F8 G8 负例：假合同 → RF_LOAN_001", code >= 400 and "RF_LOAN_001" in text,
      "HTTP {} {}".format(code, text[:200]))

# ---- F9 还款核销 ----
code, text = req("POST", FE + "/gw/api/jobs/schedule-due-rollup",
                 {"Content-Type": "application/json", "Authorization": "Bearer " + T},
                 json.dumps({"bizDate": TODAY}).encode())
code, data, text = api("POST", "/gw/api/jobs/schedule-due-rollup",
                       {"bizDate": time.strftime("%Y-%m-%d", time.gmtime(TS + 120 * 86400))},
                       token=T)
check("F9 到期滚转 job（滚至末期）", code == 200 and (data or {}).get("dueCount", 0) >= 2,
      "HTTP {} {} {}".format(code, text[:160], data))
code, data, text = api("GET", "/gw/api/repayment-plans?loanId={}".format(LOAN), token=T)
ITEMS = (data or {}).get("items") or []
check("F9 计划 3 期且全部可核销", len(ITEMS) == 3 and
      all(i.get("status") in ("DUE", "OVERDUE") for i in ITEMS),
      json.dumps(data, ensure_ascii=False)[:300])
pay_ok = True
for idx, item in enumerate(ITEMS, start=1):
    amount = item.get("amountDue", 0)
    code, data, text = api("POST", "/gw/api/payments",
                           {"loanId": LOAN, "accountRef": "ACCT-" + RUN,
                            "transactionId": "TX-{}-{}".format(RUN, idx), "amount": amount,
                            "channel": "BANK_TRANSFER",
                            "paymentRef": "PM-{}-{}".format(RUN, idx)},
                           token=T, idem="{}-pay{}".format(RUN, idx))
    if not (code == 200 and (data or {}).get("status") == "ALLOCATED"):
        pay_ok = False
        check("F9 第{}期核销".format(idx), False, "HTTP {} {}".format(code, text[:200]))
        break
    code, data, text = api("GET", "/gw/api/repayment-plans?loanId={}".format(LOAN), token=T)
    cur = ((data or {}).get("items") or [])[idx - 1]
    if not (cur.get("status") == "PAID" and cur.get("paidAmount") == amount):
        pay_ok = False
        check("F9 第{}期对拍".format(idx), False, json.dumps(cur, ensure_ascii=False)[:200])
        break
if pay_ok:
    check("F9 三期逐笔核销 + schedule_item PAID 对拍", True)
code, data, text = api("GET", "/gw/api/loans/{}/balance".format(LOAN), token=T)
check("F9 还清后余额=0", code == 200 and (data or {}).get("balance") == 0,
      "HTTP {} {}".format(code, text[:160]))

# ---- F10 结清 ----
code, data, text = api("GET", "/gw/api/loans/{}/settlement-quote".format(LOAN), token=T)
check("F10 结清试算可用", code == 200, "HTTP {} {}".format(code, text[:160]))
code, data, text = api("POST", "/gw/api/loans/{}/settle".format(LOAN),
                       {"type": "NORMAL", "waiverAmount": 0, "settlementFee": 0},
                       token=T, idem=RUN + "-settle")
check("F10 结清 SETTLED", code == 200 and (data or {}).get("status") == "SETTLED",
      "HTTP {} {}".format(code, text[:250]))

# ---- F11 解押闭环 ----
code, data, text = api("POST", "/gw/api/title-retentions",
                       {"loanId": LOAN, "vin": VIN, "contractualOnly": False}, token=T)
TITLE = (data or {}).get("titleId")
check("F11 保留登记 REGISTERED", code == 200 and TITLE and (data or {}).get("status") == "REGISTERED",
      "HTTP {} {}".format(code, text[:250]))
order = wait_until(lambda: (lambda r: (r[1] or {}).get("releaseOrderId"))(
    api("GET", "/gw/api/release-orders?loanId={}".format(LOAN), token=T)),
    "事件建单（只读端点轮询）")
check("F11 LOAN_SETTLED 事件自动建单（GET /api/release-orders 查得）", bool(order),
      "45s 内未查到解除订单")
if order:
    OID = int(order)
    docs = [{"docType": t, "attachmentId": None, "deliveredTo": "DMS-DEV-01"}
            for t in ["RELEASE_CONSENT", "TRANSFER_CERTIFICATE", "POWER_OF_ATTORNEY",
                      "SEAL_CERTIFICATE"]]
    code, data, text = api("POST", "/gw/api/release-orders/{}/documents".format(OID), docs, token=T)
    check("F11 四件套 → DOCUMENTS_PREPARED",
          code == 200 and (data or {}).get("status") == "DOCUMENTS_PREPARED",
          "HTTP {} {}".format(code, text[:250]))
    code, data, text = api("POST", "/gw/api/release-orders/{}/issue".format(OID), {}, token=T)
    check("F11 文件発行 → DOCUMENTS_ISSUED",
          code == 200 and (data or {}).get("status") == "DOCUMENTS_ISSUED",
          "HTTP {} {}".format(code, text[:250]))
    api("POST", "/gw/api/release-orders/{}/external-submission".format(OID),
        {"submissionRef": "SUB-" + RUN}, token=T)
    code, data, text = api("POST", "/gw/api/release-orders/{}/receipts".format(OID),
                           {"authority": "INTERNAL_WITNESS", "receiptRef": "WIT-" + RUN,
                            "verifiedBy": "admin", "rawRef": "raw-" + RUN}, token=T)
    check("F11 代录 → MANUAL_RECORDED 且 releaseCompleted=false",
          code == 200 and (data or {}).get("releaseCompleted") is False,
          "HTTP {} {}".format(code, text[:200]))
    code, data, text = api("POST", "/gw/api/release-orders/{}/receipts".format(OID),
                           {"authority": "PARTNER_SELF_REPORT", "receiptRef": "PSR-" + RUN,
                            "verifiedBy": "admin", "rawRef": "raw-" + RUN}, token=T)
    check("F11 X-10 负例：经销商自证 → RF_TITLE_002",
          code >= 400 and "RF_TITLE_002" in text, "HTTP {} {}".format(code, text[:160]))
    code, data, text = api("POST", "/gw/api/release-orders/{}/receipts".format(OID),
                           {"authority": "EXTERNAL_REGISTRY", "receiptRef": "REG-" + RUN,
                            "verifiedBy": "admin", "rawRef": "raw-ext-" + RUN}, token=T)
    check("F11 外部回执 → CLOSED releaseCompleted=true",
          code == 200 and (data or {}).get("status") == "CLOSED"
          and (data or {}).get("releaseCompleted") is True,
          "HTTP {} {}".format(code, text[:250]))
    code, data, text = api("GET", "/gw/api/release-orders/{}/evidence-chain".format(OID), token=T)
    check("F11 证据链 8 条 allClosed=true",
          code == 200 and (data or {}).get("allClosed") is True
          and len((data or {}).get("items") or []) == 8,
          "HTTP {} {}".format(code, text[:250]))

# ---- F12 贷后（/svc 直连段）----
code, data, text = api("POST", "/svc/api/jobs/dpd-snapshot", {"bizDate": TODAY}, token=T)
check("F12 DPD 快照 job（经 /svc 代理）", code == 200 and (data or {}).get("jobName") == "DPD_SNAPSHOT",
      "HTTP {} {}".format(code, text[:200]))
code, data, text = api("POST", "/gw/api/recon/run", {"bizDate": TODAY}, token=T)
check("F12 recon 对拍 run", code == 200 and (data or {}).get("jobName") == "RECON_INV",
      "HTTP {} {}".format(code, text[:200]))

# ---- F13 客户自助（/cust 直连段）----
code, data, text = api("POST", "/gw/api/auth/customer/login", {"username": str(CID)})
CT = (data or {}).get("token", "")
check("F13 客户登录（开发桥）", code == 200 and CT and (data or {}).get("subjectType") == "CUSTOMER",
      "HTTP {} {}".format(code, text[:200]))
code, data, text = api("GET", "/cust/api/customer-self/me", token=CT)
PII = (data or {}).get("pii") or {}
check("F13 /me 本人视图 + 姓名/电话脱敏",
      code == 200 and (data or {}).get("customerId") == CID
      and (PII.get("name") or "").startswith("山") and "***" in (PII.get("name") or "")
      and (PII.get("phone") or "").startswith("*"), "HTTP {} {}".format(code, text[:250]))
check("F13 脱敏不泄漏明文", "山田太郎" not in text and PHONE not in text, text[:200])
code, data, text = api("GET", "/cust/api/customer-self/me", token=T)
check("F13 主体隔离：员工 token 访问客户视图 → 拒绝", code >= 400,
      "HTTP {} {}".format(code, text[:160]))

# ---- 汇总 ----
print("")
print("=" * 66)
print("前端页面冒烟结果：PASS {} / FAIL {}".format(PASS, len(FAIL)))
if FAIL:
    print("失败项：")
    for f in FAIL:
        print("  - " + f)
    raise SystemExit(1)
print("页面链路全部通过 ✅")
