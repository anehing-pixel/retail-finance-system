-- ============================================================================
-- V3 · 申请域（R06 §4.2 前半；权威写者 BC-1 application-service）
-- ============================================================================
CREATE TABLE IF NOT EXISTS core.applications (
    application_id     BIGSERIAL PRIMARY KEY,
    application_no     VARCHAR(32)  NOT NULL,
    customer_id        BIGINT       NOT NULL,      -- → pii.customers（跨 schema 引用，应用层保证）
    dealer_partner_id  BIGINT,
    status             VARCHAR(24)  NOT NULL DEFAULT 'SUBMITTED',
    transaction_scheme VARCHAR(24)  NOT NULL,      -- INSTALLMENT_CREDIT / MONEY_LENDING（C-01 双形态并行）
    submitted_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    channel            VARCHAR(12)  NOT NULL DEFAULT 'PORTAL',
    idempotency_key    VARCHAR(64),
    trace_id           VARCHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_app_no UNIQUE (application_no),
    CONSTRAINT uk_app_idempotency UNIQUE (idempotency_key)
);
-- 去重窗口：同一客户的在途申请快速定位（R06 §4.2 idx_app_customer_vehicle_window）
CREATE INDEX IF NOT EXISTS idx_app_customer_window
    ON core.applications (customer_id, status, submitted_at);
CREATE INDEX IF NOT EXISTS idx_app_dealer ON core.applications (dealer_partner_id);

CREATE TABLE IF NOT EXISTS core.application_items (
    item_id            BIGSERIAL PRIMARY KEY,
    application_id     BIGINT      NOT NULL,
    vin                VARCHAR(32),
    model              VARCHAR(64),
    cash_price         BIGINT      NOT NULL DEFAULT 0,   -- 整数日元
    accessories_amount BIGINT      NOT NULL DEFAULT 0,
    trace_id           VARCHAR(64),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    version            INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_appitem_app_vin UNIQUE (application_id, vin)
);
CREATE INDEX IF NOT EXISTS idx_appitem_app ON core.application_items (application_id);
