-- V16 · PII 隔离收紧：撤销业务角色对 pii.customer_pii（个人敏感信息金库）的 SELECT
-- 背景：V1 的「ALTER DEFAULT PRIVILEGES IN SCHEMA pii GRANT SELECT ON TABLES TO retail_app_rw」
--       对 pii schema 全部表（含 customer_pii）授了 SELECT，与 R06 §3 角色表口径
--       「retail_app_rw = core 全表 + pii.customers 只读（不含 pii.customer_pii）」不一致。
-- 本迁移只调整授权：不改表结构、不改数据、不动已执行迁移字节（V1–V15 原样保留）。
--   1) 撤销业务角色对密文金库表的读权限（表内仍是字段级 AES-256-GCM 密文 + HMAC 检索列）
--   2) 撤销 schema 级默认权限：今后新建 pii 表不再自动授予业务角色 SELECT（默认拒绝）
--   3) 显式保留客户主表只读（R06 §3 口径下 pii schema 唯一合法的业务读路径）
-- 不变式：pii.customers 仍可读（core/applications 等表的 customer_id 引用源）；
--         pii.consents / identity_* 等既有授权本次不动（报发送量路径需要）。
-- 依据：R06 §3 §5、ADR-RF-004（R03 §4 切断点 ③ 的数据面）
-- ============================================================================

-- 1) 撤销业务角色对个人敏感信息金库表的读权限
REVOKE SELECT ON pii.customer_pii FROM retail_app_rw;

-- 2) 撤销 schema 级默认授权，避免后续新建 pii 表再次自动放行业务角色
ALTER DEFAULT PRIVILEGES IN SCHEMA pii REVOKE SELECT ON TABLES FROM retail_app_rw;

-- 3) 显式保留客户主表只读（幂等；权限位以 R06 §3 口径为准）
GRANT SELECT ON pii.customers TO retail_app_rw;
