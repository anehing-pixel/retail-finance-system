-- ============================================================================
-- V18 · 产品档位目录（金融产品配置）+ 还款计划快照列（W-CACHE / W-RL 本轮）
-- ----------------------------------------------------------------------------
-- 动机：还款计划的 monthlyRateBp 由调用方入参传入且**不落库**，计划生成后
--       无法复现当时算法、无法重算对账。V18 补两层：
--       ① core.financial_products —— 产品档位目录（版本化 + 生效区间，读多写少）；
--       ② core.repayment_plans 增 3 列 —— 生成计划时落**快照**（只存 product_code
--          引用不够：配置会改会下架，改配置不得改历史计划）。
-- 权威写者：BC-6 retail-core（档位快照与还款计划同一本地事务）。
-- 边界：产品表只定义「还款口径」（期数 / 月利率基点 / 允许的奖金月 / 提前结清
--       减免上限）；费用与费率属**合同条款**，以合同侧口径为准，不进产品表。
-- 号段：V20–V29 预留（R00 §7）；V18 为 V10–V19 段内新占用，V1–V17 字节不动。
-- ============================================================================

-- 产品档位目录：同码多版本（product_code + product_version 唯一），按生效区间选版
CREATE TABLE IF NOT EXISTS core.financial_products (
    product_id      BIGSERIAL PRIMARY KEY,
    product_code    VARCHAR(32) NOT NULL,
    product_version INT         NOT NULL,                -- 业务版本号（同码升序）
    product_name    VARCHAR(64) NOT NULL,
    scheme          VARCHAR(24) NOT NULL,                -- INSTALLMENT_CREDIT / MONEY_LENDING
    term_count      INT         NOT NULL,                -- 期数（36）
    rate_bp         INT         NOT NULL,                -- 月利率基点（200 = 2.0%/月；0 = 0 息）
    bonus_month     INT,                                -- 允许的奖金月（1-12）；null = 不支持
    waiver_cap_bp   INT,                                -- 提前结清减免上限（千分比）；null = 不设上限（L-12 待法务核实）
    effective_from  DATE        NOT NULL,                -- 生效日（含）
    effective_to    DATE,                                -- 失效日（不含）；null = 长期有效
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    trace_id        VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         INT         NOT NULL DEFAULT 0,      -- 乐观锁（并发改配置的最终防线）
    CONSTRAINT uk_fin_product_ver UNIQUE (product_code, product_version)
);

CREATE INDEX IF NOT EXISTS idx_fin_product_eff ON core.financial_products (product_code, effective_from);
CREATE INDEX IF NOT EXISTS idx_fin_product_sch ON core.financial_products (scheme, enabled);

-- 还款计划快照：生成计划时实际采用的档位与利率（配置变更不影响存量）
ALTER TABLE core.repayment_plans
    ADD COLUMN IF NOT EXISTS product_code    VARCHAR(32),  -- 产品档位码（纯入参模式为 null）
    ADD COLUMN IF NOT EXISTS product_version INT,          -- 档位业务版本（纯入参模式为 null）
    ADD COLUMN IF NOT EXISTS rate_bp         INT;          -- 生成时实际采用的月利率基点（快照）

-- 种子档位：汽车贷款 36 期 0 息 + 个品分期 36 期 月息 2.0%
-- 提前结清减免上限一律留空：L-12 待法务核实，系统侧不预设上限（C-10 口径）
INSERT INTO core.financial_products (product_code, product_version, product_name, scheme,
                                      term_count, rate_bp, bonus_month, waiver_cap_bp,
                                      effective_from, effective_to, enabled, trace_id)
VALUES ('AUTO_LOAN_36_0', 1, '汽车贷款 36 期 0 息', 'MONEY_LENDING',
        36, 0, NULL, NULL, DATE '2026-01-01', NULL, TRUE, 'seed-v18'),
       ('INSTALMENT_36_200', 1, '个品分期 36 期 月息 2.0%', 'INSTALLMENT_CREDIT',
        36, 200, NULL, NULL, DATE '2026-01-01', NULL, TRUE, 'seed-v18')
ON CONFLICT (product_code, product_version) DO NOTHING;
