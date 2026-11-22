-- V15 (W9-A bureau-service 报送执行闭环)
-- 1) integration.credit_bureau_reports 增 source 列（ADR-RF-005 §5 人工通道降级：
--    AUTO=自动通道（事件消费/批处理），MANUAL=人工报送+结果录入通道；
--    两通道汇入同一真相源表，source 区分留痕口径，历史行回填 AUTO）
-- 2) core.bureau_report_triggers 到期扫描索引（报送日批：status=REGISTERED 且 target_biz_date 到期）
-- 不变式不变：consent_id NOT NULL（INV-5）；uk_report_loan_trigger / uk_brt_loan_type 幂等
-- ============================================================================

ALTER TABLE integration.credit_bureau_reports
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'AUTO';

CREATE INDEX IF NOT EXISTS idx_brt_due_scan
    ON core.bureau_report_triggers (status, target_biz_date);
