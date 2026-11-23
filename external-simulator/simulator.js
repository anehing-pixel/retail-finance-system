/* eslint-disable */
/**
 * 外部机构模拟器（W-SIM）——按我方提议协议 v1 扮演外部对端，零 npm 依赖。
 *
 * 扮演两个机构（其余通道按同一模式扩展）：
 *   ① CIC 征信机构（出站对端，R07 §4.7/§4.8/§4.9）：
 *      POST /cic/v1/inquiries   征信查询（校验信封 + consentId 非空 INV-5）
 *      POST /cic/v1/reports     T+1 报送受理
 *      POST /cic/v1/disputes    异议受理
 *   ② 登记机关（入站发起方，R07 §4.14 / I-10）：
 *      POST /registry/v1/submissions  接收解除委托 → 2s 后"办结"，
 *        按我方 DMZ 签名规范（METHOD\nPATH\nts\nnonce\nkeyVersion\nsha256hex(body)
 *        的 HMAC-SHA256）回调 POST {DMZ}/external/registry/receipts
 *
 * 运行：node external-simulator/simulator.js   （端口 18100，env SIM_PORT / SIM_DMZ_URL）
 * 观测：GET /external-sim/stats  GET /external-sim/events
 */
"use strict";

const http = require("http");
const crypto = require("crypto");

const PORT = parseInt(process.env.SIM_PORT || "18100", 10);
const DMZ_URL = (process.env.SIM_DMZ_URL || "http://127.0.0.1:18091").replace(/\/$/, "");
const PARTNER_CODE = process.env.SIM_PARTNER_CODE || "REG-SIM-01";
const PARTNER_SECRET = process.env.SIM_PARTNER_SECRET || "dev-registry-sim-secret-0123456789";
const CALLBACK_PATH = "/external/registry/receipts";
const CALLBACK_DELAY_MS = parseInt(process.env.SIM_CALLBACK_DELAY_MS || "2000", 10);

const stats = { cicInquiries: 0, cicReports: 0, cicDisputes: 0, registrySubmissions: 0, callbacks: 0 };
const events = [];          // 收到的报文（观测面，含 BANDEAU 摘要不含 PII）
const seenMessageIds = new Set();   // 协议幂等：同 messageId 重放返回首次结果

function log(...args) { console.log(new Date().toISOString(), ...args); }

function readBody(req) {
  return new Promise((resolve, reject) => {
    let chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(body);
}

function ok(res, data) { send(res, 200, { code: "OK", message: "OK", data }); }
function fail(res, status, code, message) { send(res, status, { code, message }); }

/** 信封校验（提议协议 v1 §4.1）：protocolVersion/messageId/timestamp/payload 必填，messageId 幂等。 */
function validateEnvelope(bodyObj) {
  if (!bodyObj || typeof bodyObj !== "object") return "BODY_NOT_JSON";
  if (bodyObj.protocolVersion !== "1.0") return "PROTOCOL_VERSION_UNSUPPORTED";
  if (!bodyObj.messageId) return "MESSAGE_ID_REQUIRED";
  if (!bodyObj.timestamp) return "TIMESTAMP_REQUIRED";
  if (!bodyObj.payload || typeof bodyObj.payload !== "object") return "PAYLOAD_REQUIRED";
  return null;
}

/** 按 DMZ 规范签名（与 PartnerSignatureVerifier 同构，两侧共用同一规范化串）。 */
function dmzSignature(method, path, timestamp, nonce, keyVersion, body) {
  const sha256hex = crypto.createHash("sha256").update(body, "utf8").digest("hex");
  const canonical = [method, path, timestamp, nonce, keyVersion, sha256hex].join("\n");
  return crypto.createHmac("sha256", PARTNER_SECRET).update(canonical, "utf8").digest("base64");
}

/** 登记机关"办结"：签名回调我方 DMZ 回执端点（authority=EXTERNAL_REGISTRY 才是权威，C-11）。 */
function sendRegistryReceipt(submission) {
  const body = JSON.stringify({
    submissionRef: submission.submissionRef,
    releaseOrderId: submission.releaseOrderId,
    receiptRef: "REG-SIM-RCP-" + crypto.randomUUID(),
    rawRef: "raw-sim-" + Date.now(),
    authority: "EXTERNAL_REGISTRY",
    protocolVersion: "1.0",
    messageId: crypto.randomUUID(),
    timestamp: new Date().toISOString(),
  });
  const ts = String(Math.floor(Date.now() / 1000));
  const nonce = crypto.randomUUID();
  const keyVersion = "v1";
  const signature = dmzSignature("POST", CALLBACK_PATH, ts, nonce, keyVersion, body);
  const url = new URL(DMZ_URL + CALLBACK_PATH);
  const req = http.request({
    hostname: url.hostname, port: url.port, path: url.pathname, method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Partner-Code": PARTNER_CODE,
      "X-Timestamp": ts,
      "X-Nonce": nonce,
      "X-Key-Version": keyVersion,
      "X-Signature": signature,
    },
  }, (res) => {
    let buf = "";
    res.on("data", (c) => (buf += c));
    res.on("end", () => {
      stats.callbacks += 1;
      log("[callback→DMZ] status=" + res.statusCode, buf.slice(0, 200));
      events.push({ dir: "OUT-CALLBACK", status: res.statusCode, body: body.slice(0, 300) });
    });
  });
  req.on("error", (e) => log("[callback→DMZ] ERROR", String(e)));
  req.end(body);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const path = url.pathname;

  try {
    if (req.method === "GET" && (path === "/external-sim/health" || path === "/healthz")) {
      return ok(res, { service: "external-simulator", status: "UP", port: PORT, dmz: DMZ_URL });
    }
    if (req.method === "GET" && path === "/external-sim/stats") {
      return ok(res, Object.assign({ events: events.length }, stats));
    }
    if (req.method === "GET" && path === "/external-sim/events") {
      return ok(res, { items: events.slice(-50) });
    }

    const body = await readBody(req);
    let parsed = null;
    try { parsed = JSON.parse(body); } catch (e) { /* 留给信封校验报错 */ }

    // ---------- CIC 征信机构（出站对端） ----------
    if (req.method === "POST" && path === "/cic/v1/inquiries") {
      const err = validateEnvelope(parsed);
      if (err) return fail(res, 400, "RF_PROTOCOL", err);
      seenMessageIds.add(parsed.messageId);
      if (!parsed.payload.consentId || parsed.payload.consentId <= 0) {
        // INV-5：同意先行，无同意不查询
        return fail(res, 422, "RF_CONSENT", "CONSENT_ID_REQUIRED");
      }
      stats.cicInquiries += 1;
      const inquiryRef = "CIC-SIM-" + crypto.randomUUID();
      events.push({ dir: "IN-INQUIRY", caseId: parsed.payload.caseId, ref: inquiryRef });
      log("[CIC inquiry] caseId=" + parsed.payload.caseId, "ref=" + inquiryRef);
      return ok(res, {
        inquiryRef: inquiryRef,
        resultSummary: "SIM-BAND-B/" + inquiryRef,
        scoreBand: "B",
        costAmount: 500,
        acceptedAt: new Date().toISOString(),
      });
    }
    if (req.method === "POST" && path === "/cic/v1/reports") {
      const err = validateEnvelope(parsed);
      if (err) return fail(res, 400, "RF_PROTOCOL", err);
      stats.cicReports += 1;
      const reportRef = "CIC-SIM-RPT-" + crypto.randomUUID();
      events.push({ dir: "IN-REPORT", loanId: parsed.payload.loanId, trigger: parsed.payload.trigger, ref: reportRef });
      log("[CIC report] loanId=" + parsed.payload.loanId, "trigger=" + parsed.payload.trigger, "ref=" + reportRef);
      return ok(res, { reportRef: reportRef, accepted: true, acceptedAt: new Date().toISOString() });
    }
    if (req.method === "POST" && path === "/cic/v1/disputes") {
      const err = validateEnvelope(parsed);
      if (err) return fail(res, 400, "RF_PROTOCOL", err);
      stats.cicDisputes += 1;
      const disputeRef = "CIC-SIM-DSP-" + crypto.randomUUID();
      events.push({ dir: "IN-DISPUTE", disputeId: parsed.payload.disputeId, ref: disputeRef });
      return ok(res, { disputeRef: disputeRef, accepted: true, slaDays: 30 });
    }

    // ---------- 登记机关（入站发起方） ----------
    if (req.method === "POST" && path === "/registry/v1/submissions") {
      if (!parsed || !parsed.submissionRef || !parsed.releaseOrderId) {
        return fail(res, 422, "RF_PROTOCOL", "SUBMISSION_REF_AND_ORDER_ID_REQUIRED");
      }
      stats.registrySubmissions += 1;
      events.push({ dir: "IN-REGISTRY-SUBMISSION", submissionRef: parsed.submissionRef, releaseOrderId: parsed.releaseOrderId });
      log("[registry submission]", parsed.submissionRef, "orderId=" + parsed.releaseOrderId,
          "→ 回调 DMZ in " + CALLBACK_DELAY_MS + "ms");
      // 异步"办结"：现实里登记机关处理需要时间，这里延迟后回调
      setTimeout(() => sendRegistryReceipt(parsed), CALLBACK_DELAY_MS);
      return ok(res, {
        accepted: true,
        receiptCallback: CALLBACK_PATH,
        expectedAuthority: "EXTERNAL_REGISTRY",
        note: "本模拟器将在约 " + CALLBACK_DELAY_MS + "ms 后回调 DMZ 回执端点",
      });
    }

    return fail(res, 404, "RF_PROTOCOL", "UNKNOWN_ENDPOINT " + req.method + " " + path);
  } catch (e) {
    log("[error]", String(e));
    return fail(res, 500, "RF_SIM_INTERNAL", String(e));
  }
});

server.listen(PORT, "127.0.0.1", () => {
  log("外部机构模拟器就绪: http://127.0.0.1:" + PORT,
      "DMZ=" + DMZ_URL, "partner=" + PARTNER_CODE);
});
