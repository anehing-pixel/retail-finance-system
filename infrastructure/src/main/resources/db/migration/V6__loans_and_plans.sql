-- ============================================================================
-- V6 · 债权域（R06 §4.3 后半；权威写者 BC-6 retail-core；账务事务红线所在）
-- 不变式：uk_disb_loan 一合同只一次代付（INV-1）；loans.balance 由流水汇总对拍（INV-2）
-- 并发：loans / repayment_plans 更新必须 SELECT ... FOR UPDATE（R06 §7）
-- ============================================================================
CREATE TABLE IF NOT EXISTS core.loans (
    loan_id         BIGSERIAL PRIMARY KEY,
    loan_no         VARCHAR(32) NOT NULL,
    contract_id     BIGINT      NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'PENDING_DISBURSEMENT',
    principal       BIGINT      NOT NULL DEFAULT 0,   -- 整数日元
    fee_amount      BIGINT      NOT NULL DEFAULT 0,
    interest_accrued BIGINT     NOT NULL DEFAULT 0,
    balance         BIGINT      NOT NULL DEFAULT 0,   -- ≡ principal + fee + interest_accrued − 已核销（INV-2 精确相等）
    disbursed_at    TIMESTAMPTZ,
    settled_at      TIMESTAMPTZ,
    settlement_type VARCHAR(16),                      -- NORMAL / EARLY / null
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_loan_no UNIQUE (loan_no),
    CONSTRAINT uk_loan_contract UNIQUE (contract_id)
);

CREATE TABLE IF NOT EXISTS core.repayment_plans (
    plan_id           BIGSERIAL PRIMARY KEY,
    loan_id           BIGINT      NOT NULL,
    method            VARCHAR(24) NOT NULL,         -- EQUAL_PAYMENT/EQUAL_PRINCIPAL/BONUS_PAYMENT/LUMP_SUM
    installment_count INT         NOT NULL,
    first_due_date    DATE        NOT NULL,
    bonus_month       INT,                          -- 奖金还款月（1-12）
    plan_version      INT         NOT NULL DEFAULT 1,
    trace_id          VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_plan_loan_version UNIQUE (loan_id, plan_version)
);

CREATE TABLE IF NOT EXISTS core.schedule_items (
    item_id       BIGSERIAL PRIMARY KEY,
    plan_id       BIGINT      NOT NULL,
    period_no     INT         NOT NULL,
    due_date      DATE        NOT NULL,
    amount_due    BIGINT      NOT NULL DEFAULT 0,
    principal_due BIGINT      NOT NULL DEFAULT 0,
    interest_due  BIGINT      NOT NULL DEFAULT 0,
    status        VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    paid_amount   BIGINT      NOT NULL DEFAULT 0,
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_sched_plan_period UNIQUE (plan_id, period_no)
);
CREATE INDEX IF NOT EXISTS idx_sched_due ON core.schedule_items (due_date);

CREATE TABLE IF NOT EXISTS core.disbursements (
    disbursement_id BIGSERIAL PRIMARY KEY,
    loan_id         BIGINT      NOT NULL,
    payee_account_ref VARCHAR(64),                  -- 引用码，非明文账号（R03 §7）
    amount          BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    instruction_ref VARCHAR(64),
    paid_at         TIMESTAMPTZ,
    idempotency_key VARCHAR(64),
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_disb_loan UNIQUE (loan_id),       -- INV-1：一合同只一次代付
    CONSTRAINT uk_disb_idempotency UNIQUE (idempotency_key)
);
