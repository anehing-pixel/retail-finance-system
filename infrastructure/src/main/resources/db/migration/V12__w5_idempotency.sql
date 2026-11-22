-- ============================================================================
-- V12 · W5 幂等记录表（S2-W5；唯一补表——V5/V6 已覆盖合同/债权/还款计划/代付）
-- 机制：中台 IdempotencyGuard + IdempotencyStore（机制层）的 JDBC 落点；
--       写接口 Idempotency-Key + DB 唯一索引双兜底（R00 §5 / R09 §6）。
--       response_body 存首次执行的 ApiResponse JSON，重放时原样返回首次结果。
-- 号段：V10–V19 归 S2 核心链路（R00 §7），V12 为 W5 占用。
-- ============================================================================
CREATE TABLE IF NOT EXISTS core.idempotency_records (
    idempotency_key     VARCHAR(64)  PRIMARY KEY,
    request_fingerprint CHAR(64)     NOT NULL,      -- IdempotencyGuard.sha256Fingerprint
    response_body       TEXT,                        -- 首次成功结果（重放返回）
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
