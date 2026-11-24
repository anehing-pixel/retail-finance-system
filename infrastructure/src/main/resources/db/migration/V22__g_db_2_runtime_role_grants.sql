-- V22 · G-DB-2 / G-DB-6：运行期受限角色的跨 schema 最小授权（R11 P0-1）
--
-- 背景：11 个连库单元从 postgres 超管切换为受限角色后，以下真实访问路径需要补偿授权：
--   1) bureau-service（retail_app_rw）读写 integration 的征信报送 / 异议 / 报文留痕表；
--   2) underwriting-service（retail_app_rw）读写 integration.outbound_tasks（P0-7 重试调度）；
--   3) MQ 开启时各业务服务读写 integration.mq_envelope_inbox（V20 已授表权限，但缺 schema USAGE）；
--   4) customer-service（retail_pii_rw）通过共享 outbox 发布事件，并消费 MQ 信封。
--
-- 原则：只授实际使用的表，不放开 integration 的默认权限，
--       不触碰 DMZ-only 表（partners / partner_credentials / nonces / call_logs / inbound_messages）。

-- ---- retail_app_rw ----
GRANT USAGE ON SCHEMA integration TO retail_app_rw;

GRANT SELECT, INSERT, UPDATE ON
    integration.credit_bureau_reports,
    integration.bureau_disputes,
    integration.bureau_message_logs,
    integration.outbound_tasks,
    integration.mq_envelope_inbox
TO retail_app_rw;

GRANT USAGE, SELECT ON SEQUENCE
    integration.credit_bureau_reports_report_id_seq,
    integration.bureau_disputes_dispute_id_seq,
    integration.bureau_message_logs_message_id_seq,
    integration.outbound_tasks_task_id_seq
TO retail_app_rw;

-- ---- retail_pii_rw ----
-- 共享 outbox：事件载荷禁 PII（B-1），pii 角色只拿 outbox 三表的读写，不碰 core 业务表
GRANT USAGE ON SCHEMA core TO retail_pii_rw;
GRANT SELECT, INSERT, UPDATE ON
    core.outbox_events,
    core.event_consumptions,
    core.consumer_offsets
TO retail_pii_rw;
GRANT USAGE, SELECT ON SEQUENCE core.outbox_events_event_id_seq TO retail_pii_rw;

-- MQ 密文信封收件箱（customer-service 开启 MQ 时消费）
GRANT USAGE ON SCHEMA integration TO retail_pii_rw;
GRANT SELECT, INSERT, UPDATE ON integration.mq_envelope_inbox TO retail_pii_rw;
