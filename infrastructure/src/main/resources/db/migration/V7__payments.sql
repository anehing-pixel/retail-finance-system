-- ============================================================================
-- V7 · 收款域（R06 §4.4；权威写者 BC-7 retail-core；核销与债权余额同一本地事务）
-- 不变式：uk_payment_account_txn 重复入金明细去重（X-6）；uk_alloc_payment 一笔入金只核销一次
-- ============================================================================
CREATE TABLE IF NOT EXISTS core.payments (
    payment_id     BIGSERIAL PRIMARY KEY,
    payment_ref    VARCHAR(64) NOT NULL,
    account_ref    VARCHAR(64) NOT NULL,            -- 引用码
    transaction_id VARCHAR(64) NOT NULL,
    amount         BIGINT      NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL,
    channel        VARCHAR(24) NOT NULL,            -- DIRECT_DEBIT/PAYMENT_SLIP/CONVENIENCE_STORE/BANK_TRANSFER
    status         VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    raw_ref        VARCHAR(64),                     -- 入金明细原始文件/接口引用
    trace_id       VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_payment_account_txn UNIQUE (account_ref, transaction_id)
);

CREATE TABLE IF NOT EXISTS core.payment_allocations (
    allocation_id   BIGSERIAL PRIMARY KEY,
    payment_id      BIGINT      NOT NULL,
    loan_id         BIGINT      NOT NULL,
    schedule_item_id BIGINT,
    fee_amount      BIGINT      NOT NULL DEFAULT 0,
    interest_amount BIGINT      NOT NULL DEFAULT 0,
    principal_amount BIGINT     NOT NULL DEFAULT 0,
    allocated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_alloc_payment UNIQUE (payment_id)
);

CREATE TABLE IF NOT EXISTS core.direct_debit_mandates (
    mandate_id   BIGSERIAL PRIMARY KEY,
    customer_id  BIGINT      NOT NULL,
    account_ref  VARCHAR(64) NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'APPLIED',  -- APPLIED→ACTIVATED（设定约 2 个月，R-7）
    applied_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at TIMESTAMPTZ,
    bank_ref     VARCHAR(64),
    trace_id     VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_mandate_customer_account UNIQUE (customer_id, account_ref)
);

CREATE TABLE IF NOT EXISTS core.payment_slips (
    slip_id         BIGSERIAL PRIMARY KEY,
    schedule_item_id BIGINT      NOT NULL,
    slip_no         VARCHAR(32) NOT NULL,
    amount          BIGINT      NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at         TIMESTAMPTZ,
    channel         VARCHAR(24) NOT NULL DEFAULT 'PAYMENT_SLIP',
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_slip_no UNIQUE (slip_no)
);
