-- ============================================================================
-- V19 · 回调幂等：外部登记回执唯一索引（W-LOCK / ADR-RF-013）
-- 归属：title-release-service（BC-8，登记回执的权威写者）；不改 V1–V18 已执行脚本字节。
-- 号段：V18 已被 W-CACHE/W-RL 的「产品档位与还款计划快照」占用（已执行），
--       故本迁移顺延至 V19（V10–V19 段内连号，R00 §7）。
-- 口径：同一 (解除订单, 回执权威, 回执号) 只允许一条登记回执——外部方「换 nonce 重推」
--       同一业务回执时，DMZ 的 nonce 关（只挡同一 nonce 重放）挡不住，由本唯一索引兜底幂等。
-- 写法：receipt_ref 可空 → 用 COALESCE(receipt_ref, '') 表达式索引，使「无回执号的代录」
--       同样受唯一约束保护；写入侧走 ON CONFLICT DO NOTHING（原子幂等，不捕获唯一键异常）。
-- ============================================================================
CREATE UNIQUE INDEX IF NOT EXISTS uk_receipt_order_authority_ref
    ON core.registration_receipts (release_order_id, authority, COALESCE(receipt_ref, ''));
