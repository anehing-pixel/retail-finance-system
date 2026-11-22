-- ============================================================================
-- V2 · pii 六表 + 员工账号/角色（auth-service 真相源，R02 §3）+ 加密元数据 + 种子员工
-- 权威写者：BC-2 customer-service（pii.*）；auth-service（core.users / core.user_roles）
-- 硬规则：禁止明文 PII 列；检索用 HMAC-SHA256 hash 列（R06 §4.1 §5）
-- ============================================================================
-- pii.customers：客户主表（非敏感标识与状态）
CREATE TABLE IF NOT EXISTS pii.customers (
    customer_id  BIGSERIAL PRIMARY KEY,
    external_ref VARCHAR(64),
    status       VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_via  VARCHAR(24) NOT NULL DEFAULT 'PORTAL',
    trace_id     VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_customers_external_ref UNIQUE (external_ref)
);

-- pii.customer_pii：字段级 AES-256-GCM 密文 + HMAC 检索列；一客户一行
CREATE TABLE IF NOT EXISTS pii.customer_pii (
    customer_id            BIGINT PRIMARY KEY,
    encrypted_name         BYTEA,
    encrypted_name_kana    BYTEA,                   -- 假名姓名（ADR-RF-011）
    encrypted_address      BYTEA,
    encrypted_birth_date   BYTEA,
    encrypted_phone        BYTEA,
    encrypted_employer     BYTEA,
    encrypted_annual_income BYTEA,
    name_hash              CHAR(64),                -- HMAC-SHA256，精确检索用
    name_kana_hash         CHAR(64),
    key_version            VARCHAR(16) NOT NULL DEFAULT 'v1',
    trace_id               VARCHAR(64),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_cpii_customer UNIQUE (customer_id)
);
-- 密文列不建普通索引（密文检索走 *_hash）
CREATE INDEX IF NOT EXISTS idx_cpii_name_hash ON pii.customer_pii (name_hash);

-- pii.identity_verifications：身份核验（EKYC/MANUAL，C-07 文件+人工起步）
CREATE TABLE IF NOT EXISTS pii.identity_verifications (
    verification_id BIGSERIAL PRIMARY KEY,
    customer_id     BIGINT      NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    method          VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    verified_at     TIMESTAMPTZ,
    reviewer_id     VARCHAR(64),
    result_snapshot JSONB,
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_idv_customer ON pii.identity_verifications (customer_id);

-- pii.identity_documents：核验材料（附件存本地，DB 只存引用）
CREATE TABLE IF NOT EXISTS pii.identity_documents (
    document_id    BIGSERIAL PRIMARY KEY,
    verification_id BIGINT     NOT NULL,
    document_type  VARCHAR(24) NOT NULL,
    attachment_id  BIGINT,
    expiry_date    DATE,
    masked_number  VARCHAR(32),
    trace_id       VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_iddoc_verification ON pii.identity_documents (verification_id);

-- pii.consents：同意登记（append-only，撤回另表）
CREATE TABLE IF NOT EXISTS pii.consents (
    consent_id            BIGSERIAL PRIMARY KEY,
    customer_id           BIGINT      NOT NULL,
    purpose               VARCHAR(32) NOT NULL,
    consent_version       VARCHAR(16) NOT NULL DEFAULT 'v1',
    status                VARCHAR(16) NOT NULL DEFAULT 'GRANTED',
    granted_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ,
    channel               VARCHAR(24),
    evidence_attachment_id BIGINT,
    trace_id              VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_consents_customer_purpose ON pii.consents (customer_id, purpose);

-- pii.consent_withdrawals：撤回（一同意一撤回）
CREATE TABLE IF NOT EXISTS pii.consent_withdrawals (
    withdrawal_id BIGSERIAL PRIMARY KEY,
    consent_id    BIGINT      NOT NULL,
    withdrawn_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason        VARCHAR(256),
    processed_at  TIMESTAMPTZ,
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_cw_consent UNIQUE (consent_id)
);

-- ---- 员工账号与角色（R02 §3：auth-service 真相源；客户侧不设 UserRole，切断点 ③）----
CREATE TABLE IF NOT EXISTS core.users (
    user_id       BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,           -- BCrypt（$2a$，60 字符）
    display_name  VARCHAR(64)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    trace_id      VARCHAR(64),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       INT          NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS core.user_roles (
    user_id  BIGINT      NOT NULL,
    role_key VARCHAR(32) NOT NULL,                 -- 岗位角色键（R08 定义），客户侧不进本表
    PRIMARY KEY (user_id, role_key)
);

-- 种子员工（开发环境）：admin / admin123 —— 与 floorplan 同一口令约定，仅 dev
INSERT INTO core.users (username, password_hash, display_name)
SELECT 'admin', crypt('admin123', gen_salt('bf', 10)), '系统管理员'
WHERE NOT EXISTS (SELECT 1 FROM core.users WHERE username = 'admin');
INSERT INTO core.user_roles (user_id, role_key)
SELECT u.user_id, 'ADMIN' FROM core.users u
WHERE u.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM core.user_roles r WHERE r.user_id = u.user_id AND r.role_key = 'ADMIN');
