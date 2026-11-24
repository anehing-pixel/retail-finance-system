-- ============================================================================
-- V21 · G-DB-7 审计写入授权落地（R06 §3.2）
-- 背景：V1 只把 audit 两表的 INSERT/SELECT 授给 retail_audit_ins；业务角色
--       retail_app_rw 仅有 audit schema 的 USAGE，retail_pii_rw 连 USAGE 都没有。
--       当前代码是单连接池，切受限账号后 servicing-service 写 audit.audit_events、
--       customer-service 写 audit.pii_access_logs 都会因无表/序列权限失败。
-- 方案（R06 §3.2 G-DB-7 架构侧裁决 + G-DB-2 连接角色细化）：
--   1) servicing-service（retail_app_rw）→ audit.audit_events INSERT/SELECT
--   2) customer-service（retail_pii_rw）→ audit.pii_access_logs INSERT/SELECT
--   3) retail_audit_ins 保留审计专用/归档账号 → 两表 INSERT/SELECT
--   4) 两张表均显式 REVOKE UPDATE/DELETE/TRUNCATE，append-only 不变
--   5) BIGSERIAL 需要序列权限：显式授 USAGE/SELECT（V1 漏授）
-- 边界：只调整授权，不改表结构、不改数据、不动 V1–V20 已执行迁移字节。
-- ============================================================================

-- schema 使用权限（幂等；retail_pii_rw 为本次新增）
GRANT USAGE ON SCHEMA audit TO retail_app_rw, retail_pii_rw, retail_audit_ins;

-- 业务审计事件：servicing-service 以业务角色写入
GRANT INSERT, SELECT ON audit.audit_events TO retail_app_rw;
GRANT USAGE, SELECT ON SEQUENCE audit.audit_events_event_id_seq
    TO retail_app_rw, retail_audit_ins;

-- PII 访问日志：customer-service 以 PII 角色写入
GRANT INSERT, SELECT ON audit.pii_access_logs TO retail_pii_rw;
GRANT USAGE, SELECT ON SEQUENCE audit.pii_access_logs_log_id_seq
    TO retail_pii_rw, retail_audit_ins;

-- append-only 硬约束：任何运行角色都不得 UPDATE / DELETE / TRUNCATE
REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_events
    FROM retail_app_rw, retail_pii_rw, retail_audit_ins;
REVOKE UPDATE, DELETE, TRUNCATE ON audit.pii_access_logs
    FROM retail_app_rw, retail_pii_rw, retail_audit_ins;
