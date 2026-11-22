-- ============================================================================
-- V8 · 所有权保留与解除域（R06 §4.5；权威写者 BC-8 title-release-service；两态分离）
-- 不变式：release_orders.CREATED 要求 loan 终态（INV-3）；
--         registration_receipts 仅 EXTERNAL_REGISTRY 可推进 RELEASE_REGISTERED（C-11）
-- ============================================================================
CREATE TABLE IF NOT EXISTS core.title_retentions (
    title_id          BIGSERIAL PRIMARY KEY,
    loan_id           BIGINT      NOT NULL,
    vin               VARCHAR(32) NOT NULL,
    status            VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    registration_mode VARCHAR(24) NOT NULL DEFAULT 'REGISTERED',  -- C-04 裁决：REGISTERED / CONTRACTUAL_ONLY
    owner_name_ref    VARCHAR(64),                 -- 检验合格证所有者引用码（不存明文名）
    registered_at     TIMESTAMPTZ,
    released_at       TIMESTAMPTZ,
    trace_id          VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_title_loan UNIQUE (loan_id)
);
-- 两态分离：DOCUMENTS_ISSUED 只进 release_documents，不改本表状态

CREATE TABLE IF NOT EXISTS core.release_orders (
    release_order_id       BIGSERIAL PRIMARY KEY,
    loan_id                BIGINT      NOT NULL,
    title_id               BIGINT      NOT NULL,
    status                 VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    confirmed_at           TIMESTAMPTZ,
    confirmation_authority VARCHAR(32),            -- 回执权威分级（R04 §4）
    trace_id               VARCHAR(64),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_release_loan UNIQUE (loan_id)
);

CREATE TABLE IF NOT EXISTS core.release_documents (
    document_id      BIGSERIAL PRIMARY KEY,
    release_order_id BIGINT      NOT NULL,
    doc_type         VARCHAR(24) NOT NULL,         -- 解除同意书/转让证明书/授权委托书/印鉴证明书
    attachment_id    BIGINT,
    issued_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_to     VARCHAR(64),
    trace_id         VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_rdoc_order_type UNIQUE (release_order_id, doc_type)
);

CREATE TABLE IF NOT EXISTS core.registration_receipts (
    receipt_id BIGSERIAL PRIMARY KEY,
    release_order_id BIGINT      NOT NULL,
    authority  VARCHAR(32) NOT NULL,               -- EXTERNAL_REGISTRY/INTERNAL_WITNESS/PARTNER_SELF_REPORT
    receipt_ref VARCHAR(64),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_by VARCHAR(64),
    raw_ref     VARCHAR(64),                       -- 原始回执引用（附件/报文 ref）
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     INT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_receipt_authority CHECK (authority IN
        ('EXTERNAL_REGISTRY', 'INTERNAL_WITNESS', 'PARTNER_SELF_REPORT'))
);
CREATE INDEX IF NOT EXISTS idx_receipt_order ON core.registration_receipts (release_order_id);
