/* 客户管理页（员工侧）
   接口口径（与 scripts/e2e_w8.py / smoke_frontend.py 一致，全部经前端同源代理 /gw → 网关 18081）：
     POST   /api/customers                      建档（IDENTITY_VERIFY）
     GET    /api/pii/customers/{id}             脱敏视图
     GET    /api/pii/customers/{id}/full        全量视图（PII_VIEW_FULL + purpose，写审计）
     POST   /api/identity-verifications         发起核验
     POST   /api/identity-verifications/{id}/documents|start|review
     POST   /api/consents                       取得同意（CONSENT_MAINTAIN）
     GET    /api/consents/validity              有效性
     DELETE /api/consents/{id}                  语义撤回
   注意：后端未提供客户列表接口，本页只支持按 customerId 查询。 */
"use strict";

const $ = (id) => document.getElementById(id);

const S = {
  token: "",
  customerId: null,
  verificationId: null,
  consentId: null,
};

const SESSION = window.RFStaffSession ? window.RFStaffSession.require() : null;
if (SESSION) {
  S.token = SESSION.token;
  const name = SESSION.displayName || SESSION.username || "员工";
  $("staff-name").textContent = name;
  $("staff-avatar").textContent = (name.trim().charAt(0) || "·").toUpperCase();
  const who = SESSION.username && SESSION.username !== name ? name + "（" + SESSION.username + "）" : name;
  $("staff-chip").title = "当前登录员工：" + who +
    (SESSION.roles && SESSION.roles.length ? " · " + SESSION.roles.join(" / ") : "");
}

$("logout-btn").addEventListener("click", () => {
  if (window.RFStaffSession) window.RFStaffSession.clear();
  location.replace("login.html");
});

/* ---------------- 公共 ---------------- */

async function api(method, path, body) {
  const headers = { "Content-Type": "application/json" };
  if (S.token) headers["Authorization"] = "Bearer " + S.token;
  let resp, text;
  try {
    resp = await fetch(path, {
      method: method,
      headers: headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    text = await resp.text();
  } catch (e) {
    return { status: 0, data: null, body: null, text: String(e) };
  }
  let parsed = null, data = null;
  try {
    parsed = JSON.parse(text);
    data = parsed && parsed.data !== undefined ? parsed.data : null;
  } catch (e) { /* 非 JSON */ }
  return { status: resp.status, data: data, body: parsed, text: text };
}

function isOk(r) {
  return r.status === 200 && (!r.body || r.body.code === "OK");
}

function errText(r) {
  if (r.status === 0) return "无法连接网关（18081），请确认服务栈已启动";
  const b = r.body || {};
  return (b.code ? b.code + " · " : "") + (b.message || ("HTTP " + r.status));
}

function setState(id, text, kind) {
  const el = $(id);
  el.textContent = text;
  el.className = "state" + (kind ? " " + kind : "");
}

function autoPhone() {
  return "090" + String(Date.now() % 100000000).padStart(8, "0");
}

const MASKED_LABELS = {
  customerId: "客户号", name: "姓名", nameKana: "假名", address: "地址",
  birthDate: "生日", phone: "电话", employer: "雇主", annualIncome: "年收入",
};
const FULL_LABELS = MASKED_LABELS;

function renderKv(hostId, data, labels) {
  const host = $(hostId);
  host.className = "";
  host.innerHTML = "";
  const table = document.createElement("table");
  table.className = "kv";
  Object.keys(labels).forEach((key) => {
    const tr = document.createElement("tr");
    const tdLabel = document.createElement("td");
    tdLabel.textContent = labels[key];
    const tdValue = document.createElement("td");
    const v = data[key];
    tdValue.textContent = v === null || v === undefined || v === "" ? "-" : String(v);
    tr.appendChild(tdLabel);
    tr.appendChild(tdValue);
    table.appendChild(tr);
  });
  host.appendChild(table);
}

function resetOps() {
  S.verificationId = null;
  S.consentId = null;
  applyIdv(null);
  $("idv-info").className = "empty";
  $("idv-info").textContent = "先发起核验";
  $("consent-status").textContent = "未取得";
  $("consent-status").className = "tag";
  $("consent-info").className = "empty";
  $("consent-info").textContent = "用途：CREDIT_INQUIRY";
  $("consent-withdraw").disabled = true;
  setState("ops-state", "");
}

/* ---------------- 建档 ---------------- */

$("register-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const name = $("reg-name").value.trim();
  if (!name) { setState("reg-state", "请填写姓名", "bad"); $("reg-name").focus(); return; }
  const body = {
    externalRef: $("reg-ref").value.trim() || ("CUS-" + Date.now()),
    name: name,
    nameKana: $("reg-kana").value.trim(),
    address: $("reg-address").value.trim(),
    birthDate: $("reg-birth").value,
    phone: $("reg-phone").value.trim() || autoPhone(),
    employer: $("reg-employer").value.trim(),
    annualIncome: $("reg-income").value.trim(),
  };
  $("reg-submit").disabled = true;
  setState("reg-state", "建档中…");
  const r = await api("POST", "/gw/api/customers", body);
  $("reg-submit").disabled = false;
  if (!isOk(r) || !r.data || !r.data.customerId) {
    setState("reg-state", "建档失败：" + errText(r), "bad");
    return;
  }
  S.customerId = r.data.customerId;
  $("lookup-id").value = S.customerId;
  $("reg-phone").value = body.phone;
  setState("reg-state", "✅ 建档成功 customerId=" + S.customerId, "ok");
  resetOps();
  await lookup();
});

/* ---------------- 查询 ---------------- */

async function lookup() {
  const id = parseInt($("lookup-id").value, 10);
  if (!id) { setState("lookup-state", "请输入 customerId", "bad"); $("lookup-id").focus(); return; }
  setState("lookup-state", "查询中…");
  const r = await api("GET", "/gw/api/pii/customers/" + id + "?purpose=CUSTOMER_SERVICE");
  if (!isOk(r) || !r.data) {
    setState("lookup-state", "查询失败：" + errText(r), "bad");
    $("masked-view").className = "empty";
    $("masked-view").textContent = "查询失败";
    return;
  }
  S.customerId = id;
  resetOps();
  const withId = Object.assign({ customerId: id }, r.data);
  renderKv("masked-view", withId, MASKED_LABELS);
  $("full-view").className = "empty";
  $("full-view").textContent = "未查看";
  setState("lookup-state", "✅ 已加载 customerId=" + id, "ok");
}

$("lookup-btn").addEventListener("click", lookup);
$("lookup-id").addEventListener("keydown", (e) => { if (e.key === "Enter") lookup(); });

async function fullView() {
  if (!S.customerId) { setState("lookup-state", "请先查询客户", "bad"); return; }
  const purpose = $("full-purpose").value.trim();
  if (!purpose) { setState("lookup-state", "完整信息必须填写用途（R08 §5 PII-6）", "bad"); $("full-purpose").focus(); return; }
  setState("lookup-state", "读取完整信息…");
  const r = await api("GET", "/gw/api/pii/customers/" + S.customerId + "/full?purpose=" + encodeURIComponent(purpose));
  if (!isOk(r) || !r.data) {
    const msg = r.status === 403
      ? "无 PII_VIEW_FULL 权限（当前账号缺少权限点）"
      : "完整信息读取失败：" + errText(r);
    setState("lookup-state", msg, "bad");
    $("full-view").className = "empty";
    $("full-view").textContent = msg;
    return;
  }
  renderKv("full-view", r.data, FULL_LABELS);
  setState("lookup-state", "✅ 完整信息已读取（已写 PII 访问审计）", "ok");
}

$("full-btn").addEventListener("click", fullView);
$("full-purpose").addEventListener("keydown", (e) => { if (e.key === "Enter") fullView(); });

/* ---------------- 身份核验 ---------------- */

const IDV_LABELS = {
  PENDING: "待提交材料", DOCUMENTS_RECEIVED: "材料已收",
  VERIFYING: "人工核验中", VERIFIED: "已通过", REJECTED: "已拒绝",
};

function applyIdv(status) {
  const tag = $("idv-status");
  tag.textContent = status ? status + "（" + (IDV_LABELS[status] || "") + "）" : "未发起";
  tag.className = "tag" + (status === "VERIFIED" ? " ok" : status === "REJECTED" ? " bad" : "");
  $("idv-doc").disabled = !(status === "PENDING" || status === "DOCUMENTS_RECEIVED");
  $("idv-start").disabled = status !== "DOCUMENTS_RECEIVED";
  $("idv-approve").disabled = status !== "VERIFYING";
  $("idv-reject").disabled = status !== "VERIFYING";
}

async function idvRefresh() {
  if (!S.verificationId) { applyIdv(null); return; }
  const r = await api("GET", "/gw/api/identity-verifications/" + S.verificationId);
  if (!isOk(r) || !r.data) {
    setState("ops-state", "核验状态查询失败：" + errText(r), "bad");
    return;
  }
  applyIdv(r.data.status);
  $("idv-info").className = "";
  $("idv-info").textContent = "verificationId=" + r.data.verificationId +
    " · customerId=" + r.data.customerId + " · 材料 " + r.data.documentCount + " 份";
}

async function idvCreate() {
  if (!S.customerId) { setState("ops-state", "请先查询或建档客户", "bad"); return; }
  setState("ops-state", "发起核验…");
  const r = await api("POST", "/gw/api/identity-verifications", { customerId: S.customerId });
  if (!isOk(r) || !r.data || !r.data.verificationId) {
    setState("ops-state", "发起核验失败：" + errText(r), "bad");
    return;
  }
  S.verificationId = r.data.verificationId;
  setState("ops-state", "✅ 已发起核验 verificationId=" + S.verificationId, "ok");
  await idvRefresh();
}

async function idvDoc() {
  if (!S.verificationId) return;
  const expiryDate = new Date(Date.now() + 5 * 365 * 86400 * 1000).toISOString().slice(0, 10);
  setState("ops-state", "提交材料…");
  const r = await api("POST", "/gw/api/identity-verifications/" + S.verificationId + "/documents", {
    documentType: "DRIVER_LICENSE", attachmentId: null,
    expiryDate: expiryDate, maskedNumber: "****1234",
  });
  if (!isOk(r)) { setState("ops-state", "提交材料失败：" + errText(r), "bad"); return; }
  setState("ops-state", "✅ 材料已提交（DRIVER_LICENSE）", "ok");
  await idvRefresh();
}

async function idvStart() {
  if (!S.verificationId) return;
  setState("ops-state", "进入人工核验…");
  const r = await api("POST", "/gw/api/identity-verifications/" + S.verificationId + "/start", {});
  if (!isOk(r)) { setState("ops-state", "开始核验失败：" + errText(r), "bad"); return; }
  setState("ops-state", "✅ 已进入人工核验（VERIFYING）", "ok");
  await idvRefresh();
}

async function idvReview(approve) {
  if (!S.verificationId) return;
  const reviewerId = (SESSION && (SESSION.username || SESSION.displayName)) || "admin";
  setState("ops-state", approve ? "审核通过…" : "审核拒绝…");
  const r = await api("POST", "/gw/api/identity-verifications/" + S.verificationId + "/review", {
    approve: approve, reviewerId: reviewerId, note: "customer-page",
  });
  if (!isOk(r)) { setState("ops-state", "审核失败：" + errText(r), "bad"); return; }
  setState("ops-state", approve ? "✅ 审核通过（VERIFIED）" : "已拒绝（REJECTED）", approve ? "ok" : "bad");
  await idvRefresh();
}

$("idv-create").addEventListener("click", idvCreate);
$("idv-doc").addEventListener("click", idvDoc);
$("idv-start").addEventListener("click", idvStart);
$("idv-approve").addEventListener("click", () => idvReview(true));
$("idv-reject").addEventListener("click", () => idvReview(false));

/* ---------------- 征信同意 ---------------- */

async function consentGrant() {
  if (!S.customerId) { setState("ops-state", "请先查询或建档客户", "bad"); return; }
  const consentVersion = $("consent-version").value.trim() || "v1";
  const channel = $("consent-channel").value;
  const expiresAt = new Date(Date.now() + 90 * 86400 * 1000).toISOString().replace(/\.\d+Z$/, "Z");
  setState("ops-state", "取得同意…");
  const r = await api("POST", "/gw/api/consents", {
    customerId: S.customerId, purpose: "CREDIT_INQUIRY",
    consentVersion: consentVersion, channel: channel,
    evidenceAttachmentId: null, expiresAt: expiresAt,
  });
  if (!isOk(r) || !r.data || !r.data.consentId) {
    setState("ops-state", "取得同意失败：" + errText(r), "bad");
    return;
  }
  S.consentId = r.data.consentId;
  setState("ops-state", "✅ 已取得同意 consentId=" + S.consentId, "ok");
  await consentValidity();
}

async function consentValidity() {
  if (!S.customerId) { setState("ops-state", "请先查询客户", "bad"); return; }
  const r = await api("GET", "/gw/api/consents/validity?customerId=" + S.customerId + "&purpose=CREDIT_INQUIRY");
  if (!isOk(r) || !r.data) {
    setState("ops-state", "有效性查询失败：" + errText(r), "bad");
    return;
  }
  const d = r.data;
  const tag = $("consent-status");
  $("consent-info").className = "";
  if (d.valid) {
    S.consentId = d.consentId || S.consentId;
    tag.textContent = "有效";
    tag.className = "tag ok";
    $("consent-info").textContent = "consentId=" + d.consentId +
      " · purpose=" + d.purpose + " · 版本=" + d.consentVersion;
  } else {
    tag.textContent = "无效";
    tag.className = "tag bad";
    $("consent-info").textContent = "无有效同意（" + (d.reasonCode || "NO_ACTIVE_CONSENT") + "）";
  }
  $("consent-withdraw").disabled = !S.consentId;
}

async function consentWithdraw() {
  if (!S.consentId) return;
  const id = S.consentId;
  setState("ops-state", "撤回同意…");
  const r = await api("DELETE", "/gw/api/consents/" + id, { reason: "CUSTOMER_PAGE_WITHDRAW" });
  if (!isOk(r)) { setState("ops-state", "撤回失败：" + errText(r), "bad"); return; }
  S.consentId = null;
  setState("ops-state", "✅ 已撤回 consentId=" + id, "ok");
  await consentValidity();
  $("consent-withdraw").disabled = true;
}

$("consent-grant").addEventListener("click", consentGrant);
$("consent-valid").addEventListener("click", consentValidity);
$("consent-withdraw").addEventListener("click", consentWithdraw);

/* ---------------- 初始化 ---------------- */

$("reg-ref").value = "CUS-" + Date.now();
applyIdv(null);

const preset = new URLSearchParams(location.search).get("customerId");
if (preset) {
  $("lookup-id").value = preset;
  lookup();
}
