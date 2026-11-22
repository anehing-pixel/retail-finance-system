-- ============================================================================
-- V9 · 集成域 + 渠道 + 贷后 + 种子数据（R06 §4.6 §4.7 §6）
-- 权威写者：DMZ（integration.*）；application-service（dealer_*）；servicing（collection/dpd）
-- 不变式：credit_bureau_reports.consent_id NOT NULL（INV-5）；uk_nonce_partner 防重放
-- ============================================================================
CREATE TABLE IF NOT EXISTS core.dealer_partners (
    dealer_partner_id BIGSERIAL PRIMARY KEY,
    dealer_master_ref VARCHAR(64),               -- → floorplan 主数据引用码（只读投影，ADR-RF-010）
    sync_status       VARCHAR(16) NOT NULL DEFAULT 'SYNCED',  -- SYNCED/UNSYNCED（降级人工标记）
    name_snapshot     VARCHAR(128),               -- 投影快照，不反向写 floorplan
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    trace_id          VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_dealer_master_ref UNIQUE (dealer_master_ref)
);

CREATE TABLE IF NOT EXISTS core.dealer_agreements (
    agreement_id      BIGSERIAL PRIMARY KEY,
    dealer_partner_id BIGINT      NOT NULL,
    agreement_type    VARCHAR(24) NOT NULL,       -- 加盟店合同（与 floorplan 融资合同分开管理）
    effective_from    DATE        NOT NULL,
    effective_to      DATE,
    payee_account_ref VARCHAR(64),                -- 代付收款账户引用码
    scope             JSONB,                      -- 代办权限范围
    trace_id          VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_agr_dealer ON core.dealer_agreements (dealer_partner_id);

CREATE TABLE IF NOT EXISTS core.collection_cases (
    case_id    BIGSERIAL PRIMARY KEY,
    loan_id    BIGINT      NOT NULL,
    dpd_stage  VARCHAR(16) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    opened_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at  TIMESTAMPTZ,
    assigned_to VARCHAR(64),
    trace_id   VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    INT         NOT NULL DEFAULT 0
);
-- 一债权一在途催收案：PG 表内 UNIQUE 不支持 WHERE，必须建表外部分唯一索引（floorplan V19 教训）
CREATE UNIQUE INDEX IF NOT EXISTS uk_coll_loan_open
    ON core.collection_cases (loan_id) WHERE status <> 'CLOSED';

CREATE TABLE IF NOT EXISTS core.dpd_snapshots (
    snapshot_id BIGSERIAL PRIMARY KEY,
    loan_id     BIGINT      NOT NULL,
    biz_date    DATE        NOT NULL,
    dpd_days    INT         NOT NULL,
    stage       VARCHAR(16) NOT NULL,
    day_basis   VARCHAR(16) NOT NULL DEFAULT 'NATURAL',  -- 逾期天数自然日口径（Q14 同源）
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_dpd_loan_date UNIQUE (loan_id, biz_date)
);

-- ---- integration schema（DMZ 独立进程的真相源）----
CREATE TABLE IF NOT EXISTS integration.partners (
    partner_id   BIGSERIAL PRIMARY KEY,
    partner_code VARCHAR(32) NOT NULL,
    type         VARCHAR(16) NOT NULL,            -- DMS/BUREAU/BANK/EKYC/REGISTRY
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    scope        JSONB,
    ip_allowlist TEXT,
    trace_id     VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_partners_code UNIQUE (partner_code)
);

CREATE TABLE IF NOT EXISTS integration.partner_credentials (
    credential_id BIGSERIAL PRIMARY KEY,
    partner_id    BIGINT      NOT NULL,
    key_version   VARCHAR(16) NOT NULL DEFAULT 'v1',
    secret_ref    VARCHAR(128) NOT NULL,           -- 指向密钥管理设施，不明文
    valid_from    TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to      TIMESTAMPTZ,                     -- 轮换宽限期：双版本并存
    rotating      BOOLEAN     NOT NULL DEFAULT FALSE,
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_cred_partner ON integration.partner_credentials (partner_id, key_version);

CREATE TABLE IF NOT EXISTS integration.inbound_messages (
    message_id  BIGSERIAL PRIMARY KEY,
    partner_id  BIGINT      NOT NULL,
    nonce       VARCHAR(64),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    signature   VARCHAR(256),
    payload_ref VARCHAR(128),                     -- 原始报文脱敏后存引用，不落 PII 明文
    status      VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_inbound_partner ON integration.inbound_messages (partner_id, received_at);

CREATE TABLE IF NOT EXISTS integration.outbound_tasks (
    task_id       BIGSERIAL PRIMARY KEY,
    partner_id    BIGINT      NOT NULL,
    task_type     VARCHAR(32) NOT NULL,
    payload_ref   VARCHAR(128),
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count   INT         NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       INT         NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS integration.nonces (
    nonce      VARCHAR(64) NOT NULL,
    partner_id BIGINT      NOT NULL,
    used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (nonce, partner_id)
);

CREATE TABLE IF NOT EXISTS integration.call_logs (
    call_id     BIGSERIAL PRIMARY KEY,
    partner_id  BIGINT,
    direction   VARCHAR(8)  NOT NULL,            -- IN / OUT
    endpoint    VARCHAR(128) NOT NULL,
    http_status INT,
    duration_ms INT,
    result      VARCHAR(16),
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS integration.credit_bureau_reports (
    report_id   BIGSERIAL PRIMARY KEY,
    loan_id     BIGINT      NOT NULL,
    consent_id  BIGINT      NOT NULL,             -- INV-5：必填
    bureau      VARCHAR(16) NOT NULL,
    trigger     VARCHAR(24) NOT NULL,             -- ReportTrigger（R00 §3.4）
    payload_ref VARCHAR(128),
    sent_at     TIMESTAMPTZ,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    response_ref VARCHAR(128),
    trace_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_report_loan_trigger UNIQUE (loan_id, trigger)
);

CREATE TABLE IF NOT EXISTS integration.bureau_disputes (
    dispute_id     BIGSERIAL PRIMARY KEY,
    customer_ref   VARCHAR(64) NOT NULL,          -- 客户引用码，不落 PII
    bureau         VARCHAR(16) NOT NULL,
    type           VARCHAR(24) NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    due_at         TIMESTAMPTZ,                   -- 异议 SLA 30 日（C-05 裁决值）
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resolution_ref VARCHAR(128),
    trace_id       VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_dispute_due ON integration.bureau_disputes (due_at);

-- ---- 种子数据（开发环境）----
-- 2026 年主要日本节假日（营业日历口径与 floorplan 一致；不足部分随年度作业补充）
INSERT INTO core.holidays (holiday_date, name) VALUES
    ('2026-01-01', '元日'),
    ('2026-01-12', '成人の日'),
    ('2026-02-11', '建国記念の日'),
    ('2026-02-23', '天皇誕生日'),
    ('2026-03-20', '春分の日'),
    ('2026-04-29', '昭和の日'),
    ('2026-05-03', '憲法記念日'),
    ('2026-05-04', 'みどりの日'),
    ('2026-05-05', 'こどもの日'),
    ('2026-07-20', '海の日'),
    ('2026-08-11', '山の日'),
    ('2026-09-21', '敬老の日'),
    ('2026-09-22', '秋分の日'),
    ('2026-11-03', '文化の日'),
    ('2026-11-23', '勤労感謝の日')
ON CONFLICT (holiday_date) DO NOTHING;

-- 开发用 DMS partner（默认 DISABLED，S3 接通时按 R07 §5 配置启用）
INSERT INTO integration.partners (partner_code, type, status, scope)
SELECT 'DMS-DEV-01', 'DMS', 'DISABLED',
       '{"submit_application":false,"receive_decision":false,"request_disbursement":false}'::jsonb
WHERE NOT EXISTS (SELECT 1 FROM integration.partners WHERE partner_code = 'DMS-DEV-01');
