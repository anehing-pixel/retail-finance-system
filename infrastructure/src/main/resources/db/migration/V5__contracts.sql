-- ============================================================================
-- V5 · 合同域（R06 §4.3 前半；权威写者 BC-5 contract-service）
-- 不变式：SIGNED 前必须有 statutory_documents 交付行（G7 / RF_CTR_001）
-- ============================================================================
CREATE TABLE IF NOT EXISTS core.contracts (
    contract_id        BIGSERIAL PRIMARY KEY,
    contract_no        VARCHAR(32)  NOT NULL,
    application_id     BIGINT       NOT NULL,
    customer_id        BIGINT       NOT NULL,
    transaction_scheme VARCHAR(24)  NOT NULL,
    status             VARCHAR(24)  NOT NULL DEFAULT 'DRAFT',
    signed_at          TIMESTAMPTZ,
    effective_at       TIMESTAMPTZ,
    cooling_off_until  DATE,                        -- 冷静期（C-08：8 日占位，模板/参数可配）
    terms_snapshot     JSONB,                       -- 签约时点条款快照，append-only
    trace_id           VARCHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_contract_no UNIQUE (contract_no),
    CONSTRAINT uk_contract_app UNIQUE (application_id)
);

CREATE TABLE IF NOT EXISTS core.statutory_documents (
    doc_id           BIGSERIAL PRIMARY KEY,
    contract_id      BIGINT      NOT NULL,
    doc_type         VARCHAR(32) NOT NULL,         -- 缔约前书面/合同书/... 四件套口径见 R04
    template_version VARCHAR(16) NOT NULL,
    delivered_at     TIMESTAMPTZ,
    delivery_method  VARCHAR(16),
    attachment_id    BIGINT,
    acknowledged_at  TIMESTAMPTZ,
    trace_id         VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_sdoc_contract_type_version UNIQUE (contract_id, doc_type, template_version)
);
CREATE INDEX IF NOT EXISTS idx_sdoc_contract ON core.statutory_documents (contract_id);
