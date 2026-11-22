-- ============================================================================
-- V13 · S2-W6 账务闭环与解除（结清调整 / 核销豁免 / 解除订单来源与证据锚点）
-- 号段：V10–V19 归 S2 核心链路（R00 §7）；V12 已被 W5 占用，W6 顺延占 V13。
-- 只增不改：V1–V12 已执行迁移字节不动（R06 §6）。
-- 口径来源：
--   C-10 提前结清 = 剩余本金 − 减免额 + 事务手续费（R01 §6）；
--   INV-2 余额精确相等 —— 减免/豁免必须进入对拍口径，故落 settlement_adjustments；
--   两态分离 / 外部回执唯一权威（C-11）：release_orders 补来源与证据锚点列。
-- ============================================================================

-- 债权：结清三要素（减免额 / 事务手续费 / 结清类型已有 settlement_type）
ALTER TABLE core.loans
    ADD COLUMN IF NOT EXISTS waiver_amount   BIGINT NOT NULL DEFAULT 0,  -- C-10 减免额（挡板输入，结算时落定）
    ADD COLUMN IF NOT EXISTS settlement_fee  BIGINT NOT NULL DEFAULT 0;  -- C-10 事务手续费（可配置，挡板输入）

-- 期次：核销拆分列（费用→利息→本金顺序核销的对拍依据，R04 §5.2 PaymentAllocationPolicy）
-- paid_amount 保持 = paid_interest + paid_principal（V6 列语义兼容）；waived 为结清豁免侧
ALTER TABLE core.schedule_items
    ADD COLUMN IF NOT EXISTS paid_interest  BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS paid_principal BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS waived_amount BIGINT NOT NULL DEFAULT 0;

-- 结清调整（append-only，一债权一次结清）：INV-2 对拍的减免侧流水
CREATE TABLE IF NOT EXISTS core.settlement_adjustments (
    adjustment_id    BIGSERIAL PRIMARY KEY,
    loan_id          BIGINT      NOT NULL,
    settlement_type  VARCHAR(16) NOT NULL,                -- NORMAL / EARLY
    principal_waiver BIGINT      NOT NULL DEFAULT 0,      -- 本金减免（C-10 减免额）
    interest_waiver  BIGINT      NOT NULL DEFAULT 0,      -- 未到期利息豁免
    fee_waiver       BIGINT      NOT NULL DEFAULT 0,      -- 未核销费用豁免
    settlement_fee   BIGINT      NOT NULL DEFAULT 0,      -- 事务手续费（增收）
    reason           VARCHAR(64),                         -- 挡板输入备注
    trace_id         VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_settlement_loan UNIQUE (loan_id)        -- 一债权一次结清调整（幂等锚点）
);

-- 解除订单：来源（事件驱动=权威证据 / 人工挡板）、债权终态事件锚点、外部申请受理编号
ALTER TABLE core.release_orders
    ADD COLUMN IF NOT EXISTS source                 VARCHAR(16) NOT NULL DEFAULT 'MANUAL',  -- EVENT / MANUAL
    ADD COLUMN IF NOT EXISTS loan_settled_event_id  BIGINT,                                  -- LOAN_SETTLED 事件 id（证据链①②③）
    ADD COLUMN IF NOT EXISTS external_submission_ref VARCHAR(64);                            -- 外部登记申请受理编号（证据链⑥）
