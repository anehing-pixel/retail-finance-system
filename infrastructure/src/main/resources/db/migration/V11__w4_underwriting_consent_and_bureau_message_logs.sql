-- ============================================================================
-- V11 · W4 增量迁移（S2-W4；不改 V1–V9 字节，R00 §7：调整一律新建迁移）
-- 1) core.underwriting_cases 补 consent_id：X-3 要求决定时复核同意有效性，
--    案件必须持有同意锚点（应用层 INV-5 校验 + 门禁 fail-closed）。
-- 2) integration.bureau_message_logs：机构报文留痕（ADR-RF-005 §2 查询/报送/异议
--    三链路报文 append-only 留痕；挡板为合成报文，真实接入为专线报文）。
--    报文原文不进 outbox payload、不进应用日志（PII-4 / RB-5）。
-- ============================================================================

ALTER TABLE core.underwriting_cases
    ADD COLUMN IF NOT EXISTS consent_id BIGINT;

CREATE TABLE IF NOT EXISTS integration.bureau_message_logs (
    message_id   BIGSERIAL PRIMARY KEY,
    direction    VARCHAR(8)  NOT NULL,        -- OUTBOUND / INBOUND
    bureau       VARCHAR(16) NOT NULL,        -- CIC / JICC / ZENGIN
    biz_ref      VARCHAR(64) NOT NULL,        -- inquiryId / reportId / disputeId
    message_text TEXT        NOT NULL,        -- 报文原文（挡板为合成报文）
    trace_id     VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_bml_biz_ref ON integration.bureau_message_logs (biz_ref);
