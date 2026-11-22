-- ============================================================================
-- V14 · W7 增量迁移（S2-W7：贷后 servicing + 对拍 recon；不改 V1–V12 字节）
-- 号段：V13–V19 归 S2 预留（R06 §6），V13 留给 W6，V14 为 W7 占用。
--
-- 1) core.collection_actions      催收动作留痕（R04 A-13 聚合实体 CollectionAction；
--                                 R08 §7 催收必留痕；append-only，不物理删除）
-- 2) core.bureau_report_triggers  征信报送触发登记（servicing 只做触发登记与状态跟踪，
--                                 bureau-service 是执行方；R02 §6 真相源边界）
-- 3) core.recon_diffs             对拍不一致清单（recon-service 真相源；R02 §3 recon
--                                 真相源 recon_runs/recon_diffs 的落点，batch_runs 复用 V1）
-- 4) 对拍只读视图 v_recon_inv1/2/3/5（R06 §6「对拍视图」S2 预留号段的落点；
--                                 INV-4 在 recon 进程内扫 outbox payload，无需视图）
-- ============================================================================

-- ---- 1) 催收动作留痕 ----
CREATE TABLE IF NOT EXISTS core.collection_actions (
    action_id   BIGSERIAL PRIMARY KEY,
    case_id     BIGINT      NOT NULL,
    action_type VARCHAR(16) NOT NULL,              -- ASSIGN/CONTACT/PROMISE/CLOSE
    outcome     VARCHAR(24),                       -- 关案结果：REPAID / UNRECOVERABLE（结果流转）
    note        VARCHAR(256),                      -- 备注留痕，不落 PII 明文（RB-5 同源口径）
    actor       VARCHAR(64) NOT NULL,              -- 验签身份（R00 §5 / B-9）
    actor_subject VARCHAR(16) NOT NULL DEFAULT 'STAFF',
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_collact_case ON core.collection_actions (case_id);

-- ---- 2) 征信报送触发登记（servicing 侧真相源）----
CREATE TABLE IF NOT EXISTS core.bureau_report_triggers (
    trigger_id      BIGSERIAL PRIMARY KEY,
    loan_id         BIGINT      NOT NULL,
    dpd_stage       VARCHAR(16) NOT NULL,          -- 触发时的逾期分层（R00 §3.4 DpdStage）
    trigger_type    VARCHAR(24) NOT NULL,          -- DELINQUENCY（逾期报送；结清报送归 BC-6/BC-4 链路）
    consent_id      BIGINT      NOT NULL,          -- INV-5：触发登记同样必须锚定同意（RB-1）
    target_biz_date DATE        NOT NULL,          -- T+1 营业日（C-05 裁决值，JdbcBusinessCalendar）
    status          VARCHAR(16) NOT NULL DEFAULT 'REGISTERED',  -- REGISTERED / CONSUMED / BLOCKED
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_brt_loan_type UNIQUE (loan_id, trigger_type)   -- 一债权一触发（uk_report_loan_trigger 同构）
);

-- ---- 3) 对拍不一致清单（recon-service 真相源）----
CREATE TABLE IF NOT EXISTS core.recon_diffs (
    diff_id     BIGSERIAL PRIMARY KEY,
    job_name    VARCHAR(64)  NOT NULL,             -- 与 batch_runs.job_name 同键
    biz_date    DATE         NOT NULL,
    inv_code    VARCHAR(16)  NOT NULL,             -- INV-1 / INV-2 / INV-3 / INV-4 / INV-5
    entity_type VARCHAR(48)  NOT NULL,             -- LOAN / RELEASE_ORDER / TITLE_RETENTION / OUTBOX_EVENT / INQUIRY ...
    entity_id   VARCHAR(64)  NOT NULL,
    detail      JSONB,                             -- 差异明细（期望值 vs 实际值；只含业务字段，禁 PII）
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',   -- OPEN / RESOLVED（不物理删除，状态表达生命周期）
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     INT         NOT NULL DEFAULT 0
);
-- 同一不一致项只留一行 OPEN（批处理重跑不产生第二份数据，A28 的表级兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uk_recon_diff_open
    ON core.recon_diffs (inv_code, entity_type, entity_id) WHERE status = 'OPEN';

-- ---- 4) 对拍只读视图（INV 定义见 R02 §8 / R10 §4 A 组；精确相等，不容差）----

-- INV-1：一份合同的代付只发生一次，金额 = 债权本金约定（多余代付行 / 金额不等即差异）
CREATE OR REPLACE VIEW core.v_recon_inv1_disbursement AS
SELECT l.loan_id,
       l.status      AS loan_status,
       l.principal,
       (SELECT COUNT(*) FROM core.disbursements d WHERE d.loan_id = l.loan_id)  AS disb_count,
       (SELECT MAX(d.amount) FROM core.disbursements d
         WHERE d.loan_id = l.loan_id AND d.status = 'PAID')                      AS paid_amount
FROM core.loans l
WHERE (SELECT COUNT(*) FROM core.disbursements d WHERE d.loan_id = l.loan_id) > 1
   OR (EXISTS (SELECT 1 FROM core.disbursements d
                WHERE d.loan_id = l.loan_id AND d.status = 'PAID')
       AND (SELECT MAX(d.amount) FROM core.disbursements d
              WHERE d.loan_id = l.loan_id AND d.status = 'PAID') <> l.principal);

-- INV-2：balance ≡ 代付本金 + 手续费 + 利息 − 已核销本金（精确相等）
CREATE OR REPLACE VIEW core.v_recon_inv2_balance AS
SELECT l.loan_id,
       l.status  AS loan_status,
       l.balance AS actual_balance,
       l.principal + l.fee_amount + l.interest_accrued
           - COALESCE(SUM(pa.principal_amount), 0) AS expected_balance
FROM core.loans l
LEFT JOIN core.payment_allocations pa ON pa.loan_id = l.loan_id
GROUP BY l.loan_id, l.status, l.balance, l.principal, l.fee_amount, l.interest_accrued
HAVING l.balance <> l.principal + l.fee_amount + l.interest_accrued
           - COALESCE(SUM(pa.principal_amount), 0);

-- INV-3a：ReleaseOrder 必须在债权终态之后（RB-3）
CREATE OR REPLACE VIEW core.v_recon_inv3_release_gating AS
SELECT ro.release_order_id, ro.loan_id, l.status AS loan_status
FROM core.release_orders ro
JOIN core.loans l ON l.loan_id = ro.loan_id
WHERE l.status NOT IN ('SETTLED', 'EARLY_SETTLED');

-- INV-3b：RELEASE_REGISTERED 必须有 EXTERNAL_REGISTRY 权威回执（RB-4 / I-4）
CREATE OR REPLACE VIEW core.v_recon_inv3_receipt_authority AS
SELECT tr.title_id, tr.loan_id, ro.release_order_id
FROM core.title_retentions tr
JOIN core.release_orders ro ON ro.title_id = tr.title_id
WHERE tr.status = 'RELEASE_REGISTERED'
  AND NOT EXISTS (SELECT 1 FROM core.registration_receipts rr
                   WHERE rr.release_order_id = ro.release_order_id
                     AND rr.authority = 'EXTERNAL_REGISTRY');

-- INV-5：查询/报送留痕必须锚定存在且未撤销的同意（RB-1；只读引用 pii.consents 状态列，
--        不触碰 customer_pii——对拍跨真相源只读，写边界不变）
CREATE OR REPLACE VIEW core.v_recon_inv5_consent AS
SELECT 'CREDIT_INQUIRY' AS entity_type, ci.inquiry_id AS entity_id,
       ci.consent_id, ci.status AS record_status
FROM core.credit_inquiries ci
LEFT JOIN pii.consents c ON c.consent_id = ci.consent_id
WHERE ci.consent_id IS NULL OR c.consent_id IS NULL OR c.status <> 'GRANTED'
UNION ALL
SELECT 'CREDIT_BUREAU_REPORT', cbr.report_id, cbr.consent_id, cbr.status
FROM integration.credit_bureau_reports cbr
LEFT JOIN pii.consents c ON c.consent_id = cbr.consent_id
WHERE cbr.consent_id IS NULL OR c.consent_id IS NULL OR c.status <> 'GRANTED';
