-- ============================================================================
-- V20 · MQ 密文信封通道（ADR-RF-014；RocketMQ 削峰 + 控制器密文投递）
-- 权威写者：各业务服务（消费幂等记录）；中继标记由 retail-core 写
-- 不变式：① 幂等主键 (envelope_id, consumer)；② 每条 outbox 事件只中继一次（relayed_at）
-- 授权：integration schema 的默认权限只授 retail_dmz_rw，业务服务需显式授予
-- ============================================================================

-- 密文信封消费幂等表（与直投 core.event_consumptions 同构，R09 §6 幂等三层之一）
CREATE TABLE IF NOT EXISTS integration.mq_envelope_inbox (
    envelope_id VARCHAR(64)  NOT NULL,
    topic       VARCHAR(64)  NOT NULL,
    consumer    VARCHAR(64)  NOT NULL,
    status      VARCHAR(12)  NOT NULL DEFAULT 'PENDING',   -- PENDING/FAILED/DONE/DEAD
    attempts    INT          NOT NULL DEFAULT 0,
    last_error  VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (envelope_id, consumer)
);

CREATE INDEX IF NOT EXISTS idx_mq_inbox_status ON integration.mq_envelope_inbox (status);

-- 业务服务需要读写该表（integration schema 默认权限只授 retail_dmz_rw）
GRANT SELECT, INSERT, UPDATE ON integration.mq_envelope_inbox TO retail_app_rw;

-- outbox 事件中继标记：非空即已中继过（投递失败不标记，下轮重试，不丢事件）
ALTER TABLE core.outbox_events ADD COLUMN IF NOT EXISTS relayed_at TIMESTAMPTZ;

-- 待中继事件的部分索引（只覆盖 PENDING 且未中继的行）
CREATE INDEX IF NOT EXISTS idx_outbox_relay
    ON core.outbox_events (event_id) WHERE status = 'PENDING' AND relayed_at IS NULL;
