-- V17 · PII 隔离收紧（续）：撤销业务角色对 pii schema 其余敏感表的 SELECT
-- 背景：V16 已撤销 retail_app_rw 对 pii.customer_pii 的 SELECT 并撤掉 pii schema 级默认授权，
--       但 V1 建表时默认授予的 SELECT 仍留在 pii.consents / pii.consent_withdrawals /
--       pii.identity_verifications / pii.identity_documents 上，与 R06 §3 角色表口径
--       「retail_app_rw = core 全表 + pii.customers 只读」及 R08 PII-5 不一致。
-- 读路径归属核实（2026-11-23 实查代码 + 视图属主）：
--   1) 直接读写这 4 张表的只有 customer-service —— PII 归属方，生产走 retail_pii_rw
--      （customer-service 不写 core 表，可单独用 PII 角色，角色矩阵不破）；
--   2) 征信查询/报发送量路径只引用 consent_id 列与 core.v_recon_inv5_consent 视图，
--      该视图 owner = postgres，PostgreSQL 以视图属主校验底层表权限，业务角色不受影响；
--   3) customer-service 未写 core 表，pii.customers 的只读仍保留给业务角色。
-- 本迁移只调整授权：不改表结构、不改数据、不动已执行迁移字节（V1–V16 原样保留）。
-- 依据：R06 §3 §5、ADR-RF-004（R03 §4 切断点 ③ 的数据面）、R08 PII-5
-- ============================================================================

-- 同意登记与撤回：仅 PII 角色可读（报发送量锚定走 INV-5 视图，不直查表）
REVOKE SELECT ON pii.consents FROM retail_app_rw;
REVOKE SELECT ON pii.consent_withdrawals FROM retail_app_rw;

-- 身份核验与核验材料：仅 PII 角色可读
REVOKE SELECT ON pii.identity_verifications FROM retail_app_rw;
REVOKE SELECT ON pii.identity_documents FROM retail_app_rw;
