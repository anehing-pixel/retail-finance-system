# -*- coding: utf-8 -*-
"""模拟对接 E2E（W-SIM）：系统 ⇄ 外部机构模拟器双向真实调用。

出站：征信查询/报送经 HttpCicAdapter → 模拟器 /cic/v1/**（提议协议 v1 信封）。
入站：解除委托 → 模拟器 /registry/v1/submissions → 2s 后按 DMZ 签名规范
      回调 /external/registry/receipts（过三关）→ 自动推进解押闭环。

前置：run-microservices.sh 已带模拟器拉起全栈（RETAIL_BUREAU_CIC_URL 指向模拟器）。
用法：python external-simulator/demo_e2e.py   （Exit 0 = 全部断言通过）
"""
import json
import sys
import time
import urllib.error
import urllib.request

GW = "http://127.0.0.1:18081"
DMZ = "http://127.0.0.1:18091"
SIM = "http://127.0.0.1:18100"

PASS = 0
FAIL = 0


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("PASS  " + name + (("  | " + detail) if detail else ""))
    else:
        FAIL += 1
        print("FAIL  " + name + (("  | " + detail) if detail else ""))


def call(method, url, body=None, token=None, idem=None, headers=None):
    hdrs = {"Content-Type": "application/json"}
    if token:
        hdrs["Authorization"] = "Bearer " + token
    if idem:
        hdrs["Idempotency-Key"] = idem
    if headers:
        hdrs.update(headers)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}
    except Exception as e:
        return 0, {"message": str(e)}


def data_of(resp):
    return (resp or {}).get("data") or {}


def wait_until(fn, desc, timeout_s, interval=1.0):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        v = fn()
        if v:
            return v
        time.sleep(interval)
    return None


TS = str(int(time.time()))
TAG = "sim-" + TS

# ---------- 0. 模拟器健康 ----------
code, resp = call("GET", SIM + "/external-sim/health")
check("01 模拟器在线", code == 200 and data_of(resp).get("status") == "UP")

# ---------- 1. DMZ 三关负例（未签名 / 坏签名） ----------
code, resp = call("POST", DMZ + "/external/registry/receipts",
                  {"releaseOrderId": 1, "receiptRef": "NEG"})
check("02 DMZ 未签名请求被拒", code >= 400 and "RF_PTNR" in json.dumps(resp),
      json.dumps(resp)[:80])
code, resp = call("POST", DMZ + "/external/registry/receipts",
                  {"releaseOrderId": 1, "receiptRef": "NEG"},
                  headers={"X-Partner-Code": "REG-SIM-01", "X-Timestamp": str(int(time.time())),
                           "X-Nonce": "neg-nonce-" + TAG, "X-Key-Version": "v1",
                           "X-Signature": "AAAA_BAD_SIGNATURE="})
check("03 DMZ 坏签名被拒(RF_PTNR_001)", code >= 400 and "RF_PTNR_001" in json.dumps(resp),
      json.dumps(resp)[:80])

# ---------- 2. 员工登录 + 主链路 S1–S5 ----------
code, resp = call("POST", GW + "/api/auth/staff/login",
                  {"username": "admin", "password": "admin123"})
token = data_of(resp).get("token", "")
check("04 员工登录", code == 200 and token)

code, resp = call("POST", GW + "/api/customers", {
    "externalRef": "EXT-" + TAG, "name": "外部模拟客户", "nameKana": "シミュレーション",
    "address": "東京都港区", "birthDate": "1991-02-02",
    "phone": "080" + TS[-8:].zfill(8), "employer": "SIM株式会社",
    "annualIncome": "7000000"}, token=token)
customer_id = str(data_of(resp).get("customerId"))
check("05 客户注册", code == 200 and customer_id, "customerId=" + str(customer_id))

code, resp = call("POST", GW + "/api/identity-verifications", {"customerId": customer_id}, token=token)
vid = data_of(resp).get("verificationId")
for step_body in [
    {"documentType": "DRIVER_LICENSE", "attachmentId": None, "expiryDate": "2030-12-31", "maskedNumber": "****9999"},
    {},
    {"approve": True, "reviewerId": "admin", "note": "sim-e2e"},
]:
    call("POST", GW + "/api/identity-verifications/" + str(vid) +
         ("/documents" if step_body.get("documentType") else
          "/start" if "approve" not in step_body else "/review"),
         step_body, token=token)
check("06 身份核验完成", True, "vid=" + str(vid))

expires = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 90 * 86400))
code, resp = call("POST", GW + "/api/consents", {
    "customerId": customer_id, "purpose": "CREDIT_INQUIRY", "consentVersion": "v1",
    "channel": "PORTAL", "evidenceAttachmentId": None, "expiresAt": expires}, token=token)
consent_id = str(data_of(resp).get("consentId"))
check("07 征信同意", code == 200 and consent_id, "consentId=" + str(consent_id))

code, resp = call("POST", GW + "/api/applications", {
    "customerId": customer_id, "dealerPartnerId": None, "transactionScheme": "INSTALLMENT_CREDIT",
    "channel": "PORTAL", "consentId": consent_id, "vin": "VIN-SIM-" + TS,
    "model": "SIM-MODEL", "cashPrice": 1500000, "accessoriesAmount": 50000},
    token=token, idem=TAG + "-app")
application_id = str(data_of(resp).get("applicationId"))
check("08 申请提交", code == 200 and application_id, "applicationId=" + str(application_id))

# ---------- 3. 出站闭环①：征信查询走模拟器 ----------
code, resp = call("POST", GW + "/api/underwriting/cases",
                  {"applicationId": application_id, "consentId": consent_id}, token=token)
case_id = str(data_of(resp).get("caseId"))
check("09 审查建案", code == 200 and case_id, "caseId=" + str(case_id))

call("POST", GW + "/api/underwriting/cases/" + case_id + "/start-inquiry", {}, token=token)
code, resp = call("POST", GW + "/api/credit-inquiries",
                  {"caseId": case_id, "consentId": consent_id, "bureau": "CIC",
                   "purpose": "CREDIT_INQUIRY"}, token=token)
inquiry_id = data_of(resp).get("inquiryId")
check("10 征信查询受理", code == 200 and inquiry_id, "inquiryId=" + str(inquiry_id))

code, resp = call("GET", SIM + "/external-sim/events")
inq_events = [e for e in data_of(resp).get("items", []) if e.get("dir") == "IN-INQUIRY"]
check("11 结果来自模拟器(非挡板)", bool(inq_events) and str(inq_events[-1].get("ref", "")).startswith("CIC-SIM"),
      json.dumps(inq_events[-1])[:80] if inq_events else "无查询事件")

done = wait_until(lambda: call("GET", GW + "/api/underwriting/cases/" + case_id, token=token)[1]
                  .get("data", {}).get("status") == "INQUIRY_DONE", "INQUIRY_DONE", 40)
check("12 事件驱动 INQUIRY_DONE", bool(done))

code, resp = call("GET", SIM + "/external-sim/stats")
sim_stats = data_of(resp)
check("13 模拟器收到查询(出站真打通)", sim_stats.get("cicInquiries", 0) >= 1,
      json.dumps(sim_stats))

code, resp = call("POST", GW + "/api/underwriting/cases/" + case_id + "/affordability",
                  {"scheme": "INSTALLMENT_CREDIT", "annualIncomeEncRef": "INC-" + TAG,
                   "existingDebtTotal": 0, "thresholdAmount": 5000000,
                   "requestedAmount": 1550000}, token=token)
code, resp = call("POST", GW + "/api/underwriting/cases/" + case_id + "/decision",
                  {"outcome": "APPROVED", "reasonCode": "SIM-E2E"}, token=token)
check("14 审查 APPROVED", code == 200 and data_of(resp).get("status") == "APPROVED")

# ---------- 4. 合同/账务/还款/结清 ----------
code, resp = call("POST", GW + "/api/contracts", {
    "applicationId": application_id, "customerId": customer_id,
    "transactionScheme": "INSTALLMENT_CREDIT"}, token=token)
contract_id = str(data_of(resp).get("contractId"))
for doc in ["PRE_CONTRACT_STATEMENT", "CONTRACT_DOCUMENT"]:
    call("POST", GW + "/api/contracts/" + contract_id + "/documents",
         {"docType": doc, "templateVersion": "SIM-1.0", "deliveryMethod": "PAPER",
          "attachmentId": None}, token=token)
call("POST", GW + "/api/contracts/" + contract_id + "/documents-delivered", {}, token=token)
call("POST", GW + "/api/contracts/" + contract_id + "/sign", {}, token=token)
call("POST", GW + "/api/contracts/" + contract_id + "/cooling-off/confirm", {}, token=token)
check("15 合同 EFFECTIVE", True, "contractId=" + str(contract_id))

code, resp = call("POST", GW + "/api/loans",
                  {"contractId": contract_id, "principal": 1550000, "feeAmount": 0}, token=token)
loan_id = str(data_of(resp).get("loanId"))
today = time.strftime("%Y-%m-%d")
call("POST", GW + "/api/repayment-plans",
     {"loanId": loan_id, "method": "EQUAL_PAYMENT", "installmentCount": 3,
      "firstDueDate": today, "monthlyRateBp": 0, "bonusMonth": None}, token=token)
code, resp = call("POST", GW + "/api/disbursements",
                  {"loanId": loan_id, "contractId": contract_id, "underwritingCaseId": case_id,
                   "payeeAccountRef": "SIM-ACCT-" + TAG}, token=token, idem=TAG + "-disb")
check("16 三门禁代付 PAID", code == 200 and data_of(resp).get("status") == "PAID")

# 保留登记在结清前创建（业务顺序：登记随放款成立；否则 LOAN_SETTLED 消费者会按 W6 兜底补 UNKNOWN 登记）
code, resp = call("POST", GW + "/api/title-retentions",
                  {"loanId": loan_id, "vin": "VIN-SIM-" + TS, "contractualOnly": False}, token=token)
check("17 保留登记 REGISTERED", code == 200 and data_of(resp).get("status") == "REGISTERED",
      json.dumps(resp)[:120])

future = time.strftime("%Y-%m-%d", time.gmtime(time.time() + 120 * 86400))
call("POST", GW + "/api/jobs/schedule-due-rollup", {"bizDate": today}, token=token)
call("POST", GW + "/api/jobs/schedule-due-rollup", {"bizDate": future}, token=token)
code, resp = call("GET", GW + "/api/repayment-plans?loanId=" + str(loan_id), token=token)
items = data_of(resp).get("items", [])
repay_ok = len(items) == 3
for i, item in enumerate(items):
    code, resp = call("POST", GW + "/api/payments",
                      {"loanId": loan_id, "accountRef": "SIM-" + TAG,
                       "transactionId": "TX-" + TAG + "-" + str(i + 1),
                       "amount": item["amountDue"], "channel": "BANK_TRANSFER",
                       "paymentRef": "PM-" + TAG + "-" + str(i + 1)},
                      token=token, idem=TAG + "-pay" + str(i + 1))
    repay_ok = repay_ok and code == 200 and data_of(resp).get("status") == "ALLOCATED"
check("18 三期核销完成", repay_ok)

code, resp = call("POST", GW + "/api/loans/" + str(loan_id) + "/settle",
                  {"type": "NORMAL", "waiverAmount": 0, "settlementFee": 0},
                  token=token, idem=TAG + "-settle")
check("19 结清 SETTLED", code == 200 and data_of(resp).get("status") == "SETTLED")

# ---------- 5. 出站闭环②：结清触发报送走模拟器 ----------
rpt = wait_until(lambda: call("GET", SIM + "/external-sim/stats")[1]
                 .get("data", {}).get("cicReports", 0) >= 1, "CIC 报送", 30)
check("20 模拟器收到报送(LOAN_SETTLED→CIC HTTP)", bool(rpt))

# ---------- 6. 入站闭环③：模拟器回调 DMZ ----------
order = wait_until(lambda: data_of(call("GET", GW + "/api/release-orders?loanId=" + str(loan_id),
                                        token=token)[1]).get("releaseOrderId"), "事件建单", 45)
check("21 LOAN_SETTLED 事件建单", bool(order), "orderId=" + str(order))
order_id = str(order)

for doc_type in ["RELEASE_CONSENT", "TRANSFER_CERTIFICATE", "POWER_OF_ATTORNEY", "SEAL_CERTIFICATE"]:
    call("POST", GW + "/api/release-orders/" + str(order_id) + "/documents",
         [{"docType": doc_type, "attachmentId": None, "deliveredTo": "SIM-REG"}], token=token)
call("POST", GW + "/api/release-orders/" + str(order_id) + "/issue", {}, token=token)
call("POST", GW + "/api/release-orders/" + str(order_id) + "/external-submission",
     {"submissionRef": "SUB-" + TAG}, token=token)

# 代录 ≠ 完成（C-11）：人工 INTERNAL_WITNESS 回执只到 MANUAL_RECORDED
code, resp = call("POST", GW + "/api/release-orders/" + str(order_id) + "/receipts",
                  {"authority": "INTERNAL_WITNESS", "receiptRef": "WIT-" + TAG,
                   "verifiedBy": "admin", "rawRef": "raw-" + TAG}, token=token)
check("22 代录≠完成", code == 200 and data_of(resp).get("status") == "MANUAL_RECORDED"
      and data_of(resp).get("releaseCompleted") is False)

# X-10：经销商自证被拒
code, resp = call("POST", GW + "/api/release-orders/" + str(order_id) + "/receipts",
                  {"authority": "PARTNER_SELF_REPORT", "receiptRef": "PSR-" + TAG,
                   "verifiedBy": "admin", "rawRef": "raw-" + TAG}, token=token)
check("23 X-10 自证被拒", code >= 400 and "RF_TITLE_002" in json.dumps(resp))

# 入站闭环：解除委托送登记机关（模拟器）→ 办结 → 签名回调 DMZ → 自动 CLOSED
code, resp = call("POST", SIM + "/registry/v1/submissions",
                  {"submissionRef": "SUB-" + TAG, "releaseOrderId": order_id,
                   "protocolVersion": "1.0", "messageId": "MSG-" + TAG,
                   "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())})
check("24 登记机关受理委托", code == 200 and data_of(resp).get("accepted") is True)

closed = wait_until(lambda: (lambda r: data_of(r[1]).get("status") == "CLOSED")(
    call("GET", GW + "/api/release-orders?loanId=" + str(loan_id), token=token)),
    "模拟器回调→CLOSED", 30)
check("25 模拟器回执自动闭环 CLOSED", bool(closed))

code, resp = call("GET", GW + "/api/release-orders/" + str(order_id) + "/evidence-chain", token=token)
check("26 证据链 allClosed", code == 200 and data_of(resp).get("allClosed") is True)

code, resp = call("GET", SIM + "/external-sim/stats")
final_stats = data_of(resp)
check("27 模拟器回调已发出", final_stats.get("callbacks", 0) >= 1, json.dumps(final_stats))

code, resp = call("GET", SIM + "/external-sim/events")
cb = [e for e in data_of(resp).get("items", []) if e.get("dir") == "OUT-CALLBACK"]
check("28 回调被 DMZ 接受(200)", bool(cb) and cb[-1].get("status") == 200,
      json.dumps(cb[-1])[:100] if cb else "无回调记录")

print("\n===== demo_e2e: %d checks, %d PASS, %d FAIL =====" % (PASS + FAIL, PASS, FAIL))
sys.exit(1 if FAIL else 0)
