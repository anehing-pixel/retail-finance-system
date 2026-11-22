package com.company.retail.servicing.domain;

/**
 * 逾期分层（R00 §3.4 DpdStage；R04 §5.2 DpdCalculator「自然日 DPD / M1–M3 分层」）。
 *
 * <p>分层口径（⚙️ 默认参数，走配置覆盖不改代码，P-12）：0 = CURRENT、1–30 = M1、
 * 31–60 = M2、61–90 = M3、91+ = M4。day_basis = NATURAL（自然日，V9 迁移口径，Q14 同源）。</p>
 */
public enum DpdStage {

    CURRENT,
    M1,
    M2,
    M3,
    M4;

    /** 自然日 → 分层映射（分层阈值如需调整走配置，不写死在调用方）。 */
    public static DpdStage fromDays(int dpdDays) {
        if (dpdDays <= 0) {
            return CURRENT;
        }
        if (dpdDays <= 30) {
            return M1;
        }
        if (dpdDays <= 60) {
            return M2;
        }
        if (dpdDays <= 90) {
            return M3;
        }
        return M4;
    }
}
