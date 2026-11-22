-- ============================================================================
-- V4 · 审查域（R06 §4.2 后半；权威写者 BC-3 underwriting-service，BC-4 bureau 读写 inquiries）
-- 不变式：credit_inquiries.consent_id NOT NULL（INV-5：无同意不得查询）
-- ============================================================================
CREATE TABLE IF NOT EXISTS core.underwriting_cases (
    case_id      BIGSERIAL PRIMARY KEY,
    application_id BIGINT    NOT NULL,
    status       VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    outcome      VARCHAR(24),
    decided_by   VARCHAR(64),
    reviewed_by  VARCHAR(64),                        -- 职责分离：decided_by <> reviewed_by（应用层 SOD）
    decided_at   TIMESTAMPTZ,
    rule_version VARCHAR(16),
    trace_id     VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_uwc_app UNIQUE (application_id)
);

CREATE TABLE IF NOT EXISTS core.affordability_assessments (
    assessment_id       BIGSERIAL PRIMARY KEY,
    case_id             BIGINT      NOT NULL,
    annual_income_enc_ref VARCHAR(64),             -- 收入证明引用码，不存明文
    existing_debt_total BIGINT      NOT NULL DEFAULT 0,
    threshold_amount    BIGINT      NOT NULL DEFAULT 0,
    result              VARCHAR(16) NOT NULL,
    policy_version      VARCHAR(16) NOT NULL,
    input_snapshot      JSONB,                       -- 公式口径 C-03：框架在，参数可配，公式不写死
    trace_id            VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_aff_case_version UNIQUE (case_id, policy_version)
);

CREATE TABLE IF NOT EXISTS core.credit_inquiries (
    inquiry_id        BIGSERIAL PRIMARY KEY,
    case_id           BIGINT      NOT NULL,
    consent_id        BIGINT      NOT NULL,        -- INV-5：必填
    bureau            VARCHAR(16) NOT NULL,        -- CIC / JICC / ZENGIN
    purpose           VARCHAR(24) NOT NULL,
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at      TIMESTAMPTZ,
    status            VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    result_snapshot_ref VARCHAR(64),
    cost_amount       BIGINT      NOT NULL DEFAULT 0,
    trace_id          VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_inq_case ON core.credit_inquiries (case_id);

CREATE TABLE IF NOT EXISTS core.income_documents (
    document_id   BIGSERIAL PRIMARY KEY,
    case_id       BIGINT      NOT NULL,
    document_type VARCHAR(24) NOT NULL,
    attachment_id BIGINT,
    fiscal_year   INT,
    verified_by   VARCHAR(64),
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_incdoc_case ON core.income_documents (case_id);

CREATE TABLE IF NOT EXISTS core.decision_rules (
    rule_id        BIGSERIAL PRIMARY KEY,
    rule_version   VARCHAR(16) NOT NULL,
    scheme         VARCHAR(24) NOT NULL,           -- 适用交易形态（C-01 双形态）
    config         JSONB       NOT NULL,           -- 规则参数版本化（P-12：口径变更走配置）
    effective_from DATE        NOT NULL,
    enabled        BOOLEAN     NOT NULL DEFAULT TRUE,
    trace_id       VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_rule_version UNIQUE (rule_version, scheme)
);
