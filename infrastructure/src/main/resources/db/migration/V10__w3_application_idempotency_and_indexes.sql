-- ============================================================================
-- V10 · S2-W3 增量迁移（R06 §6 号段 V10–V19 预留内的首个增量）
-- 归属：application-service（申请幂等指纹列 + 车辆检索索引）；不改 V1–V9 已执行脚本。
-- 不新增表名（R00 §3.3 集合不变）：幂等指纹直接落 core.applications 既有
-- idempotency_key 旁，避免为单一写接口新开表。
-- ============================================================================

-- 申请请求指纹（IdempotencyGuard：同键同指纹=重放，同键异指纹=RF_CONFLICT_001 冲突）
ALTER TABLE core.applications
    ADD COLUMN IF NOT EXISTS request_fingerprint CHAR(64);

-- 受理时声明的同意引用（G1 闸门留痕：同意有效性由 customer-service 门禁数据面校验）
ALTER TABLE core.applications
    ADD COLUMN IF NOT EXISTS consent_ref BIGINT;

-- 重复申请检测（同一人+同一車+窗口期，R04 §5.2 DuplicateApplicationDetector）
-- 按 VIN 快速定位在途申请（application_items 此前只有 (application_id, vin) 复合唯一）
CREATE INDEX IF NOT EXISTS idx_appitem_vin ON core.application_items (vin);
