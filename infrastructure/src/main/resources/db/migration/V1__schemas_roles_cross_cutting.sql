-- ============================================================================
-- V1 · 四 schema + role 授权 + 横切表（R06 §3 §4.8 §6）
-- 权威写者：横切表各归其主；audit 仅 INSERT（append-only 由 role 兜底）
-- 通用列口径（R06 §4）：id BIGSERIAL PK / created_at / updated_at TIMESTAMPTZ /
--   version INT NOT NULL DEFAULT 0 / trace_id VARCHAR(64)
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS pii;
CREATE SCHEMA IF NOT EXISTS integration;
CREATE SCHEMA IF NOT EXISTS audit;

-- ---- DB role（开发环境口令为 dev 默认值，生产必须替换；R06 §3）----
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'retail_app_rw') THEN
    CREATE ROLE retail_app_rw LOGIN PASSWORD 'retail_app_rw_dev';
  END IF;
END $$;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'retail_pii_rw') THEN
    CREATE ROLE retail_pii_rw LOGIN PASSWORD 'retail_pii_rw_dev';
  END IF;
END $$;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'retail_dmz_rw') THEN
    CREATE ROLE retail_dmz_rw LOGIN PASSWORD 'retail_dmz_rw_dev';
  END IF;
END $$;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'retail_audit_ins') THEN
    CREATE ROLE retail_audit_ins LOGIN PASSWORD 'retail_audit_ins_dev';
  END IF;
END $$;

GRANT USAGE ON SCHEMA core TO retail_app_rw;
GRANT USAGE ON SCHEMA pii TO retail_app_rw;
GRANT USAGE ON SCHEMA pii TO retail_pii_rw;
GRANT USAGE ON SCHEMA integration TO retail_dmz_rw;
GRANT USAGE ON SCHEMA audit TO retail_audit_ins, retail_app_rw;

-- PII 隔离（切断点 ③ 的数据面）：业务角色只读 customers 主表，绝不授 customer_pii
ALTER DEFAULT PRIVILEGES IN SCHEMA pii GRANT SELECT ON TABLES TO retail_app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA pii GRANT ALL ON TABLES TO retail_pii_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA pii GRANT ALL ON SEQUENCES TO retail_pii_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA core GRANT ALL ON TABLES TO retail_app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA core GRANT ALL ON SEQUENCES TO retail_app_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA integration GRANT ALL ON TABLES TO retail_dmz_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA integration GRANT ALL ON SEQUENCES TO retail_dmz_rw;

-- ---- 横切表 ----
CREATE TABLE IF NOT EXISTS audit.audit_events (
    event_id       BIGSERIAL PRIMARY KEY,
    actor          VARCHAR(64)  NOT NULL,
    actor_subject  VARCHAR(16)  NOT NULL,            -- CUSTOMER/STAFF/SYSTEM/PARTNER
    actor_role     VARCHAR(64),
    action         VARCHAR(64)  NOT NULL,
    target_type    VARCHAR(48),
    target_id      VARCHAR(64),
    before_snapshot JSONB,
    after_snapshot  JSONB,
    trace_id       VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- append-only：只授 INSERT + SELECT，物理层禁止 UPDATE/DELETE
REVOKE UPDATE, DELETE ON audit.audit_events FROM PUBLIC;
GRANT INSERT, SELECT ON audit.audit_events TO retail_audit_ins;

CREATE TABLE IF NOT EXISTS audit.pii_access_logs (
    log_id        BIGSERIAL PRIMARY KEY,
    actor         VARCHAR(64)  NOT NULL,
    actor_subject VARCHAR(16)  NOT NULL,
    customer_id   BIGINT       NOT NULL,
    fields        VARCHAR(256) NOT NULL,            -- 访问字段清单，不记明文值
    purpose       VARCHAR(64),
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
REVOKE UPDATE, DELETE ON audit.pii_access_logs FROM PUBLIC;
GRANT INSERT, SELECT ON audit.pii_access_logs TO retail_audit_ins;

CREATE TABLE IF NOT EXISTS core.outbox_events (
    event_id      BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(64)  NOT NULL,
    aggregate_key VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL,            -- JSON 文本，发布前过白名单（INV-4）
    status        VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at       TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_outbox_type_agg ON core.outbox_events (event_type, aggregate_key);

CREATE TABLE IF NOT EXISTS core.event_consumptions (
    event_id   BIGINT      NOT NULL,
    consumer   VARCHAR(64) NOT NULL,
    status     VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    attempts   INT         NOT NULL DEFAULT 0,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE TABLE IF NOT EXISTS core.consumer_offsets (
    consumer      VARCHAR(64) PRIMARY KEY,
    last_event_id BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS core.holidays (
    holiday_date DATE PRIMARY KEY,
    name         VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS core.batch_runs (
    run_id      BIGSERIAL PRIMARY KEY,
    job_name    VARCHAR(64)  NOT NULL,
    biz_date    DATE         NOT NULL,
    status      VARCHAR(12)  NOT NULL DEFAULT 'RUNNING',
    started_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    trace_id    VARCHAR(64),
    CONSTRAINT uk_batch_job_date UNIQUE (job_name, biz_date)
);

-- 附件走本地文件系统 data/attachments/（ADR-RF-008），DB 只存相对路径与哈希
CREATE TABLE IF NOT EXISTS core.attachments (
    attachment_id BIGSERIAL PRIMARY KEY,
    biz_type      VARCHAR(48) NOT NULL,
    biz_id        VARCHAR(64) NOT NULL,
    relative_path VARCHAR(256) NOT NULL,
    sha256        CHAR(64)    NOT NULL,
    size_bytes    BIGINT      NOT NULL,
    uploaded_by   VARCHAR(64) NOT NULL,
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_attachments_biz ON core.attachments (biz_type, biz_id);
