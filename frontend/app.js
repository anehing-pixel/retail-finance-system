/* 新车零售金融 · 运营控制台（本地验证用）
   请求口径与 scripts/e2e_w8.py 完全一致，路径前缀：
   /gw → 网关 18081；/svc → servicing 8098；/cust → customer 8092。 */
"use strict";

const S = {
  token: "", custToken: "",
  ids: {},            // customer_id / verification_id / consent_id / application_id / case_id / contract_id / loan_id / title_id / release_order_id
  ts: Math.floor(Date.now() / 1000),
  vin: "",
  planItems: [],      // 还款计划期次（核销用）
};

const $ = (id) => document.getElementById(id);

function runTag() { return "fe-" + S.ts; }

function log(method, path, info, isErr) {
  const body = $("log-body");
  const div = document.createElement("div");
  if (isErr) div.className = "err";
  div.textContent = "[" + method + "] " + path + "  →  " + info;
  body.appendChild(div);
  body.parentElement.scrollTop = body.parentElement.scrollHeight;
}

function setState(id, text, cls) {
  const el = $(id);
  el.textContent = text;
  el.className = cls || "";
}

function chip(label, value) {
  const el = document.createElement("span");
  el.className = "chip" + (value === undefined ? " gray" : "");
  el.textContent = value === undefined ? label : label + ": " + value;
  return el;
}

function renderChips() {
  let host = document.querySelector("#all-state");
  const ids = S.ids;
  const map = [
    ["customerId", ids.customer_id], ["applicationId", ids.application_id],
    ["caseId", ids.case_id], ["contractId", ids.contract_id],
    ["loanId", ids.loan_id], ["titleId", ids.title_id],
    ["releaseOrderId", ids.release_order_id],
  ];
  const bar = document.querySelector(".chips") || (() => {
    const c = document.createElement("div");
    c.className = "chips";
    document.querySelector("#btn-e2e").parentElement.appendChild(c);
    return c;
  })();
  bar.innerHTML = "";
  map.forEach(([k, v]) => bar.appendChild(chip(k, v)));
}

async function call(method, path, body, opts) {
  opts = opts || {};
  const headers = { "Content-Type": "application/json" };
  if (opts.token) headers["Authorization"] = "Bearer " + opts.token;
  if (opts.idem) headers["Idempotency-Key"] = opts.idem;
  const t0 = performance.now();
  let code = 0, text = "";
  try {
    const r = await fetch(path, {
      method: method, headers: headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    code = r.status;
    text = await r.text();
  } catch (e) { text = String(e); }
  const ms = Math.round(performance.now() - t0);
  log(method, path, code + " (" + ms + "ms)", code === 0 || code >= 500);
  let data = null;
  try { data = (JSON.parse(text) || {}).data ?? null; } catch (e) { /* 非 JSON */ }
  return { code: code, data: data, text: text };
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function waitUntil(fn, desc, timeoutMs) {
  const deadline = Date.now() + (timeoutMs || 30000);
  while (Date.now() < deadline) {
    const v = await fn();
    if (v) return v;
    await sleep(1000);
  }
  console.warn("[waitUntil 超时] " + desc);
  return null;
}

function showTab(name) {
  ["staff", "self", "help"].forEach((t) => {
    $("pane-" + t).style.display = t === name ? "" : "none";
    $("tab-" + t).classList.toggle("active", t === name);
  });
}

function requireToken() {
  if (!S.token) { setState("all-state", "请先执行 S1 登录", "step-bad"); return false; }
  return true;
}

function setPlanTable(items) {
  const host = $("s9-plan");
  host.innerHTML = "";
  const table = document.createElement("table");
  table.innerHTML = "<tr><th>期次</th><th>到期日</th><th>应还（円）</th><th>已还（円）</th><th>状态</th></tr>";
  (items || []).forEach((i, idx) => {
    const tr = document.createElement("tr");
    tr.innerHTML = "<td>" + (idx + 1) + "</td><td>" + (i.dueDate || "-") + "</td><td>" +
      i.amountDue + "</td><td>" + (i.paidAmount || 0) + "</td><td>" +
      '<span class="badge">' + i.status + "</span></td>";
    table.appendChild(tr);
  });
  host.appendChild(table);
}

/* ---------------- S1 登录 ---------------- */

async function s1Login() {
  const r = await call("POST", "/gw/api/auth/staff/login",
    { username: $("staff-user").value, password: $("staff-pass").value });
  const token = (r.data || {}).token || "";
  const ok = r.code === 200 && token && (r.data || {}).subjectType === "EMPLOYEE";
  S.token = ok ? token : "";
  setState("s1-state", ok ? "✅ 员工 token 已取得" : "❌ 登录失败 " + r.code,
    ok ? "step-ok" : "step-bad");
  return ok;
}

/* ---------------- S2 客户注册 ---------------- */

async function s2Register() {
  if (!requireToken()) return false;
  const phone = "090" + String(S.ts % 100000000).padStart(8, "0");
  const r = await call("POST", "/gw/api/customers", {
    externalRef: "EXT-" + runTag(),
    name: $("cust-name").value, nameKana: $("cust-kana").value,
    address: "東京都千代田区丸の内1-1-1", birthDate: "1990-01-01",
    phone: phone, employer: "フロント株式会社", annualIncome: "6000000",
  }, { token: S.token });
  const cid = (r.data || {}).customerId;
  const ok = r.code === 200 && cid;
  if (ok) S.ids.customer_id = cid;
  setState("s2-state", ok ? "✅ customerId=" + cid : "❌ " + r.code,
    ok ? "step-ok" : "step-bad");
  renderChips();
  return ok;
}

/* ---------------- S3 身份核验 ---------------- */

async function s3All() {
  if (!requireToken() || !S.ids.customer_id) {
    setState("s3-state", "缺前置（S1/S2）", "step-bad"); return false;
  }
  const T = S.token, cid = S.ids.customer_id;
  let r = await call("POST", "/gw/api/identity-verifications", { customerId: cid }, { token: T });
  const vid = (r.data || {}).verificationId;
  if (!(r.code === 200 && vid)) { setState("s3-state", "❌ 发起核验 " + r.code, "step-bad"); return false; }
  S.ids.verification_id = vid;
  r = await call("POST", "/gw/api/identity-verifications/" + vid + "/documents",
    { documentType: "DRIVER_LICENSE", attachmentId: null, expiryDate: "2030-12-31",
      maskedNumber: "****1234" }, { token: T });
  r = await call("POST", "/gw/api/identity-verifications/" + vid + "/start", {}, { token: T });
  r = await call("POST", "/gw/api/identity-verifications/" + vid + "/review",
    { approve: true, reviewerId: "admin", note: "console" }, { token: T });
  r = await call("GET", "/gw/api/identity-verifications/" + vid, null, { token: T });
  const ok = r.code === 200 && (r.data || {}).status === "VERIFIED";
  setState("s3-state", ok ? "✅ VERIFIED" : "❌ " + (r.data || {}).status, ok ? "step-ok" : "step-bad");
  return ok;
}

/* ---------------- S4 同意 ---------------- */

async function s4Consent() {
  if (!requireToken() || !S.ids.customer_id) {
    setState("s4-state", "缺前置（S1/S2）", "step-bad"); return false;
  }
  const T = S.token;
  const expires = new Date(Date.now() + 90 * 86400 * 1000).toISOString().replace(/\.\d+Z$/, "Z");
  let r = await call("POST", "/gw/api/consents", {
    customerId: S.ids.customer_id, purpose: "CREDIT_INQUIRY",
    consentVersion: "v1", channel: "PORTAL", evidenceAttachmentId: null, expiresAt: expires,
  }, { token: T });
  const consentId = (r.data || {}).consentId;
  if (!(r.code === 200 && consentId)) { setState("s4-state", "❌ " + r.code, "step-bad"); return false; }
  S.ids.consent_id = consentId;
  r = await call("GET", "/gw/api/consents/validity?customerId=" + S.ids.customer_id +
    "&purpose=CREDIT_INQUIRY", null, { token: T });
  const ok = r.code === 200 && (r.data || {}).valid === true;
  setState("s4-state", ok ? "✅ 同意有效 consentId=" + consentId : "❌ 有效性校验失败",
    ok ? "step-ok" : "step-bad");
  renderChips();
  return ok;
}

/* ---------------- S5 申请 ---------------- */

async function s5Apply() {
  if (!requireToken() || !S.ids.customer_id || !S.ids.consent_id) {
    setState("s5-state", "缺前置（S2/S4）", "step-bad"); return false;
  }
  const T = S.token;
  S.vin = "VIN-FE-" + S.ts;
  const price = parseInt($("app-price").value || "1200000", 10);
  const acc = parseInt($("app-access").value || "30000", 10);
  const body = {
    customerId: S.ids.customer_id, dealerPartnerId: null,
    transactionScheme: "INSTALLMENT_CREDIT", channel: "PORTAL",
    consentId: S.ids.consent_id, vin: S.vin, model: "FE-CONSOLE-MODEL",
    cashPrice: price, accessoriesAmount: acc,
  };
  let r = await call("POST", "/gw/api/applications", body,
    { token: T, idem: runTag() + "-app" });
  const appId = (r.data || {}).applicationId;
  if (!(r.code === 200 && appId)) { setState("s5-state", "❌ " + r.code, "step-bad"); return false; }
  S.ids.application_id = appId;
  // 幂等重放（页面级验证）
  r = await call("POST", "/gw/api/applications", body, { token: T, idem: runTag() + "-app" });
  const ok = (r.data || {}).replayed === true && (r.data || {}).applicationId === appId;
  setState("s5-state", ok ? "✅ 申请号 " + (r.data || {}).applicationNo + "（幂等重放 ✓）"
    : "⚠️ 提交成功但幂等重放异常", ok ? "step-ok" : "step-note");
  renderChips();
  return true;
}

/* ---------------- S6 审查 ---------------- */

async function s6All() {
  if (!requireToken() || !S.ids.application_id || !S.ids.consent_id) {
    setState("s6-state", "缺前置（S4/S5）", "step-bad"); return false;
  }
  const T = S.token, appId = S.ids.application_id;
  let r = await call("POST", "/gw/api/underwriting/cases",
    { applicationId: appId, consentId: S.ids.consent_id }, { token: T });
  const caseId = (r.data || {}).caseId;
  if (!(r.code === 200 && caseId)) { setState("s6-state", "❌ 建案 " + r.code, "step-bad"); return false; }
  S.ids.case_id = caseId;
  r = await call("POST", "/gw/api/underwriting/cases/" + caseId + "/start-inquiry", {}, { token: T });
  r = await call("POST", "/gw/api/credit-inquiries",
    { caseId: caseId, consentId: S.ids.consent_id, bureau: "CIC", purpose: "CREDIT_INQUIRY" },
    { token: T });
  if (r.code !== 200) { setState("s6-state", "❌ 征信查询 " + r.code, "step-bad"); return false; }
  // 事件驱动推进 → INQUIRY_DONE（页面轮询案件状态，最长 40s）
  const done = await waitUntil(async () => {
    const g = await call("GET", "/gw/api/underwriting/cases/" + caseId, null, { token: T });
    return (g.data || {}).status === "INQUIRY_DONE";
  }, "案件 INQUIRY_DONE", 40000);
  if (!done) { setState("s6-state", "❌ 事件未推进（40s）", "step-bad"); return false; }
  const principal = parseInt($("app-price").value || "1200000", 10) +
    parseInt($("app-access").value || "30000", 10);
  r = await call("POST", "/gw/api/underwriting/cases/" + caseId + "/affordability",
    { scheme: "INSTALLMENT_CREDIT", annualIncomeEncRef: "INC-" + runTag(),
      existingDebtTotal: 0, thresholdAmount: 5000000, requestedAmount: principal }, { token: T });
  r = await call("POST", "/gw/api/underwriting/cases/" + caseId + "/decision",
    { outcome: "APPROVED", reasonCode: "FE-CONSOLE" }, { token: T });
  const ok = r.code === 200 && (r.data || {}).status === "APPROVED";
  setState("s6-state", ok ? "✅ APPROVED（决定人 " + (r.data || {}).decidedBy + "）" : "❌ " + r.code,
    ok ? "step-ok" : "step-bad");
  renderChips();
  return ok;
}

/* ---------------- S7 合同 ---------------- */

async function s7All() {
  if (!requireToken() || !S.ids.application_id) {
    setState("s7-state", "缺前置（S5）", "step-bad"); return false;
  }
  const T = S.token;
  let r = await call("POST", "/gw/api/contracts",
    { applicationId: S.ids.application_id, customerId: S.ids.customer_id,
      transactionScheme: "INSTALLMENT_CREDIT" }, { token: T });
  const contractId = (r.data || {}).contractId;
  if (!(r.code === 200 && contractId)) { setState("s7-state", "❌ " + r.code, "step-bad"); return false; }
  S.ids.contract_id = contractId;
  for (const docType of ["PRE_CONTRACT_STATEMENT", "CONTRACT_DOCUMENT"]) {
    r = await call("POST", "/gw/api/contracts/" + contractId + "/documents",
      { docType: docType, templateVersion: "C08-1.0", deliveryMethod: "PAPER",
        attachmentId: null }, { token: T });
  }
  r = await call("POST", "/gw/api/contracts/" + contractId + "/documents-delivered", {}, { token: T });
  r = await call("POST", "/gw/api/contracts/" + contractId + "/sign", {}, { token: T });
  const signed = r.code === 200 && (r.data || {}).status === "COOLING_OFF";
  r = await call("POST", "/gw/api/contracts/" + contractId + "/cooling-off/confirm", {}, { token: T });
  r = await call("GET", "/gw/api/contracts/" + contractId + "/disbursement-gate", null, { token: T });
  const ok = signed && r.code === 200 && (r.data || {}).allowed === true;
  setState("s7-state", ok ? "✅ EFFECTIVE，代付门禁 allowed=true" : "❌ " + r.code,
    ok ? "step-ok" : "step-bad");
  renderChips();
  return ok;
}

/* ---------------- S8 账务 ---------------- */

async function s8Loan() {
  if (!requireToken() || !S.ids.contract_id) {
    setState("s8-state", "缺前置（S7）", "step-bad"); return false;
  }
  const T = S.token, principal = parseInt($("app-price").value || "1200000", 10) +
    parseInt($("app-access").value || "30000", 10);
  let r = await call("POST", "/gw/api/loans",
    { contractId: S.ids.contract_id, principal: principal, feeAmount: 0 }, { token: T });
  const loanId = (r.data || {}).loanId;
  if (!(r.code === 200 && loanId)) { setState("s8-state", "❌ " + r.code, "step-bad"); return false; }
  S.ids.loan_id = loanId;
  const today = new Date().toISOString().slice(0, 10);
  r = await call("POST", "/gw/api/repayment-plans",
    { loanId: loanId, method: "EQUAL_PAYMENT", installmentCount: 3, firstDueDate: today,
      monthlyRateBp: 0, bonusMonth: null }, { token: T });
  const ok = r.code === 200 && (r.data || {}).totalAmountDue === principal;
  setState("s8-state", ok ? "✅ loanId=" + loanId + "，还款计划总额 " + principal + " 円"
    : "❌ " + r.code, ok ? "step-ok" : "step-bad");
  renderChips();
  return ok;
}

async function s8Disburse() {
  if (!requireToken() || !S.ids.loan_id || !S.ids.case_id) {
    setState("s8-state", "缺前置（S6/S8）", "step-bad"); return false;
  }
  const r = await call("POST", "/gw/api/disbursements",
    { loanId: S.ids.loan_id, contractId: S.ids.contract_id,
      underwritingCaseId: S.ids.case_id, payeeAccountRef: "DEALER-ACCT-" + runTag() },
    { token: S.token, idem: runTag() + "-disb" });
  const ok = r.code === 200 && (r.data || {}).status === "PAID";
  setState("s8-state", ok ? "✅ PAID " + (r.data || {}).amount + " 円，债权 " +
    (r.data || {}).loanStatus : "❌ " + r.code + " " + ((r.text || "").match(/RF_[A-Z]+_\d+/) || [""])[0],
    ok ? "step-ok" : "step-bad");
  return ok;
}

async function s8NegContract() {
  if (!requireToken()) return false;
  const r = await call("POST", "/gw/api/disbursements",
    { loanId: 1, contractId: 999999999, underwritingCaseId: S.ids.case_id || 1,
      payeeAccountRef: "NEG-" + runTag() }, { token: S.token, idem: runTag() + "-neg" });
  const ok = r.code >= 400 && /RF_LOAN_001/.test(r.text);
  setState("s8-state", ok ? "✅ 门禁 fail-closed（RF_LOAN_001）" : "❌ 未按预期拒绝 " + r.code,
    ok ? "step-ok" : "step-bad");
  return ok;
}

/* ---------------- S9 还款 ---------------- */

async function s9All() {
  if (!requireToken() || !S.ids.loan_id) {
    setState("s9-state", "缺前置（S8）", "step-bad"); return false;
  }
  const T = S.token, loanId = S.ids.loan_id;
  const today = new Date().toISOString().slice(0, 10);
  const future = new Date(Date.now() + 120 * 86400 * 1000).toISOString().slice(0, 10);
  let r = await call("POST", "/gw/api/jobs/schedule-due-rollup", { bizDate: today }, { token: T });
  r = await call("POST", "/gw/api/jobs/schedule-due-rollup", { bizDate: future }, { token: T });
  r = await call("GET", "/gw/api/repayment-plans?loanId=" + loanId, null, { token: T });
  const items = (r.data || {}).items || [];
  S.planItems = items;
  setPlanTable(items);
  let allOk = items.length === 3;
  for (let idx = 0; idx < items.length; idx++) {
    const item = items[idx];
    const amount = item.amountDue;
    r = await call("POST", "/gw/api/payments",
      { loanId: loanId, accountRef: "ACCT-" + runTag(),
        transactionId: "TX-" + runTag() + "-" + (idx + 1), amount: amount,
        channel: "BANK_TRANSFER", paymentRef: "PM-" + runTag() + "-" + (idx + 1) },
      { token: T, idem: runTag() + "-pay" + (idx + 1) });
    if (!(r.code === 200 && (r.data || {}).status === "ALLOCATED")) { allOk = false; break; }
    r = await call("GET", "/gw/api/repayment-plans?loanId=" + loanId, null, { token: T });
    const cur = ((r.data || {}).items || [])[idx];
    setPlanTable((r.data || {}).items || []);
    if (!cur || cur.status !== "PAID") { allOk = false; break; }
    const paidSoFar = items.slice(0, idx + 1).reduce((s, i) => s + i.amountDue, 0);
    r = await call("GET", "/gw/api/loans/" + loanId + "/balance", null, { token: T });
    if ((r.data || {}).balance !== items.reduce((s, i) => s + i.amountDue, 0) - paidSoFar) {
      allOk = false; break;
    }
  }
  setState("s9-state", allOk ? "✅ 3 期核销完成，余额对拍一致" : "❌ 核销/对拍中断",
    allOk ? "step-ok" : "step-bad");
  return allOk;
}

/* ---------------- S10 结清 ---------------- */

async function s10Settle() {
  if (!requireToken() || !S.ids.loan_id) {
    setState("s10-state", "缺前置（S9）", "step-bad"); return false;
  }
  const T = S.token, loanId = S.ids.loan_id;
  let r = await call("GET", "/gw/api/loans/" + loanId + "/settlement-quote", null, { token: T });
  r = await call("POST", "/gw/api/loans/" + loanId + "/settle",
    { type: "NORMAL", waiverAmount: 0, settlementFee: 0 },
    { token: T, idem: runTag() + "-settle" });
  const ok = r.code === 200 && (r.data || {}).status === "SETTLED";
  setState("s10-state", ok ? "✅ SETTLED（quote=" + (r.data || {}).quote + "）" : "❌ " + r.code,
    ok ? "step-ok" : "step-bad");
  return ok;
}

/* ---------------- S11 解押 ---------------- */

async function s11Register() {
  if (!requireToken() || !S.ids.loan_id) {
    setState("s11-state", "缺前置（S8）", "step-bad"); return false;
  }
  const r = await call("POST", "/gw/api/title-retentions",
    { loanId: S.ids.loan_id, vin: S.vin, contractualOnly: false }, { token: S.token });
  const titleId = (r.data || {}).titleId;
  const ok = r.code === 200 && titleId && (r.data || {}).status === "REGISTERED";
  if (ok) S.ids.title_id = titleId;
  setState("s11-state", ok ? "✅ 保留登记 REGISTERED titleId=" + titleId : "❌ " + r.code,
    ok ? "step-ok" : "step-bad");
  renderChips();
  return ok;
}

async function s11WaitOrder() {
  if (!requireToken() || !S.ids.loan_id) {
    setState("s11-state", "缺前置（S8）", "step-bad"); return false;
  }
  // 事件建单轮询：GET /gw/api/release-orders?loanId=（只读端点）直到 data 非空
  const order = await waitUntil(async () => {
    const g = await call("GET", "/gw/api/release-orders?loanId=" + S.ids.loan_id,
      null, { token: S.token });
    return (g.data && g.data.releaseOrderId) ? g.data : null;
  }, "EVENT 来源解除订单", 45000);
  if (!order) { setState("s11-state", "❌ 45s 内未建单（LOAN_SETTLED 未消费？）", "step-bad"); return false; }
  S.ids.release_order_id = order.releaseOrderId;
  setState("s11-state", "✅ 事件建单 orderId=" + order.releaseOrderId + "（source=" + order.source + "）",
    "step-ok");
  renderChips();
  return true;
}

async function s11All() {
  if (!requireToken() || !S.ids.release_order_id) {
    setState("s11-state", "缺前置（S11 建单）", "step-bad"); return false;
  }
  const T = S.token, oid = S.ids.release_order_id;
  const docs = ["RELEASE_CONSENT", "TRANSFER_CERTIFICATE", "POWER_OF_ATTORNEY", "SEAL_CERTIFICATE"]
    .map((t) => ({ docType: t, attachmentId: null, deliveredTo: "DMS-DEV-01" }));
  let r = await call("POST", "/gw/api/release-orders/" + oid + "/documents", docs, { token: T });
  r = await call("POST", "/gw/api/release-orders/" + oid + "/issue", {}, { token: T });
  const issued = r.code === 200 && (r.data || {}).status === "DOCUMENTS_ISSUED";
  r = await call("POST", "/gw/api/release-orders/" + oid + "/external-submission",
    { submissionRef: "SUB-" + runTag() }, { token: T });
  // 代录 ≠ 完成
  r = await call("POST", "/gw/api/release-orders/" + oid + "/receipts",
    { authority: "INTERNAL_WITNESS", receiptRef: "WIT-" + runTag(),
      verifiedBy: "admin", rawRef: "raw-" + runTag() }, { token: T });
  const manual = r.code === 200 && (r.data || {}).status === "MANUAL_RECORDED" &&
    (r.data || {}).releaseCompleted === false;
  r = await call("GET", "/gw/api/title-retentions?loanId=" + S.ids.loan_id, null, { token: T });
  const stillRegistered = (r.data || {}).status === "REGISTERED";
  // 经销商自证被拒（X-10）
  r = await call("POST", "/gw/api/release-orders/" + oid + "/receipts",
    { authority: "PARTNER_SELF_REPORT", receiptRef: "PSR-" + runTag(),
      verifiedBy: "admin", rawRef: "raw-" + runTag() }, { token: T });
  const selfReportRejected = r.code >= 400 && /RF_TITLE_002/.test(r.text);
  // 外部登记回执 → 闭环
  r = await call("POST", "/gw/api/release-orders/" + oid + "/receipts",
    { authority: "EXTERNAL_REGISTRY", receiptRef: "REG-" + runTag(),
      verifiedBy: "admin", rawRef: "raw-ext-" + runTag() }, { token: T });
  const closed = r.code === 200 && (r.data || {}).status === "CLOSED" &&
    (r.data || {}).releaseCompleted === true;
  r = await call("GET", "/gw/api/release-orders/" + oid + "/evidence-chain", null, { token: T });
  const chainOk = r.code === 200 && (r.data || {}).allClosed === true &&
    (((r.data || {}).items) || []).length === 8;
  const ok = issued && manual && stillRegistered && selfReportRejected && closed && chainOk;
  setState("s11-state", ok
    ? "✅ 解押闭环（代录≠完成 ✓ / 自证拒绝 ✓ / 回执闭环 ✓ / 证据链 8 条 allClosed ✓）"
    : "❌ 中断（issued=" + issued + " manual=" + manual + " registered=" + stillRegistered +
      " reject=" + selfReportRejected + " closed=" + closed + " chain=" + chainOk + ")",
    ok ? "step-ok" : "step-bad");
  return ok;
}

/* ---------------- S12 贷后 ---------------- */

async function s12All() {
  if (!requireToken() || !S.ids.loan_id) {
    setState("s12-state", "缺前置（S8）", "step-bad"); return false;
  }
  const T = S.token, today = new Date().toISOString().slice(0, 10);
  let r = await call("POST", "/svc/api/jobs/dpd-snapshot", { bizDate: today }, { token: T });
  const dpdOk = r.code === 200 && (r.data || {}).jobName === "DPD_SNAPSHOT";
  r = await call("POST", "/gw/api/recon/run", { bizDate: today }, { token: T });
  const reconOk = r.code === 200 && (r.data || {}).jobName === "RECON_INV";
  r = await call("GET", "/gw/api/recon/diffs?status=OPEN", null, { token: T });
  const opens = Array.isArray(r.data) ? r.data : [];
  const mine = opens.filter((d) => String(d.entityId) === String(S.ids.loan_id));
  const ok = dpdOk && reconOk && mine.length === 0;
  setState("s12-state", ok ? "✅ DPD 快照 + 对拍 run 完成，本债权无 OPEN 差异"
    : "❌ dpd=" + dpdOk + " recon=" + reconOk + " openDiff=" + mine.length,
    ok ? "step-ok" : "step-bad");
  return ok;
}

/* ---------------- 一键全链路 ---------------- */

async function runAll() {
  $("btn-e2e").disabled = true;
  const steps = [
    ["S1", s1Login, "s1-state"], ["S2", s2Register, "s2-state"],
    ["S3", s3All, "s3-state"], ["S4", s4Consent, "s4-state"],
    ["S5", s5Apply, "s5-state"], ["S6", s6All, "s6-state"],
    ["S7", s7All, "s7-state"], ["S8a", s8Loan, "s8-state"],
    ["S8b", s8Disburse, "s8-state"], ["S9", s9All, "s9-state"],
    ["S10", s10Settle, "s10-state"], ["S11a", s11Register, "s11-state"],
    ["S11b", s11WaitOrder, "s11-state"], ["S11c", s11All, "s11-state"],
    ["S12", s12All, "s12-state"],
  ];
  let failed = "";
  for (const [name, fn] of steps) {
    setState("all-state", "运行 " + name + " …");
    const ok = await fn();
    if (!ok) { failed = name; break; }
  }
  setState("all-state", failed ? "❌ 在 " + failed + " 中断（见对应步骤）" : "✅ 全链路 S1–S12 全部通过",
    failed ? "step-bad" : "step-ok");
  $("btn-e2e").disabled = false;
}

function resetRun() {
  S.token = ""; S.custToken = ""; S.ids = {};
  S.ts = Math.floor(Date.now() / 1000);
  S.vin = ""; S.planItems = [];
  ["s1-state", "s2-state", "s3-state", "s4-state", "s5-state", "s6-state", "s7-state",
   "s8-state", "s9-state", "s10-state", "s11-state", "s12-state", "all-state", "self-state"]
    .forEach((id) => setState(id, ""));
  setPlanTable([]);
  $("self-view").innerHTML = "";
  renderChips();
}

/* ---------------- 客户自助 ---------------- */

async function selfLogin() {
  const cid = $("self-cust").value || S.ids.customer_id;
  if (!cid) { setState("self-state", "请填 customerId", "step-bad"); return false; }
  const r = await call("POST", "/gw/api/auth/customer/login", { username: String(cid) });
  const token = (r.data || {}).token || "";
  const ok = r.code === 200 && token && (r.data || {}).subjectType === "CUSTOMER";
  S.custToken = ok ? token : "";
  setState("self-state", ok ? "✅ 客户 token（customerId=" + cid + "）" : "❌ " + r.code,
    ok ? "step-ok" : "step-bad");
  return ok;
}

async function selfMe() {
  if (!S.custToken) { setState("self-state", "请先客户登录", "step-bad"); return false; }
  const r = await call("GET", "/cust/api/customer-self/me", null, { token: S.custToken });
  const pii = (r.data || {}).pii || {};
  const host = $("self-view");
  host.innerHTML = "";
  const table = document.createElement("table");
  table.innerHTML = "<tr><th>字段</th><th>脱敏值</th></tr>" +
    "<tr><td>customerId</td><td>" + (r.data || {}).customerId + "</td></tr>" +
    "<tr><td>姓名</td><td>" + (pii.name || "-") + "</td></tr>" +
    "<tr><td>电话</td><td>" + (pii.phone || "-") + "</td></tr>";
  host.appendChild(table);
  const ok = r.code === 200 && (r.data || {}).customerId !== undefined;
  setState("self-state", ok ? "✅ 脱敏视图已渲染" : "❌ " + r.code, ok ? "step-ok" : "step-bad");
  return ok;
}

async function selfNegative() {
  if (!S.token) { setState("self-state", "负例需员工 token（S1）", "step-bad"); return false; }
  const r = await call("GET", "/cust/api/customer-self/me", null, { token: S.token });
  const ok = r.code >= 400;
  setState("self-state", ok ? "✅ 主体隔离生效：员工 token 被拒（HTTP " + r.code + "）"
    : "❌ 应被拒绝但返回 " + r.code, ok ? "step-ok" : "step-bad");
  return ok;
}
