package com.company.retail.core.domain;

/**
 * 还款参数合成策略（纯领域函数，零 Spring 依赖，可纯 JUnit 跑）。
 *
 * <p><b>优先级：授信覆盖值 &gt; 产品档位默认值</b>。
 * 「授信覆盖」是针对个案的人工调整（如活动降息、缩短期数）；
 * 「产品档位」是公开的默认口径。三者都进快照，便于复现当时为什么是这个利率。</p>
 *
 * <p><b>为什么不能只靠入参</b>：入参不落库时，计划生成后无法复现当时算法，
 * 客户争议「当时到底几息」只能靠日志追；落快照才能复现与重算。</p>
 */
public final class ProductTermsResolver {

    private ProductTermsResolver() {
    }

    /**
     * 档位 + 覆盖值合成生效口径。
     *
     * @param profile           产品档位档案（当前生效版）
     * @param overrideTermCount 覆盖期数；null = 用档位默认
     * @param overrideRateBp    覆盖月利率基点；null = 用档位默认
     * @param overrideBonusMonth 覆盖奖金月；null = 用档位默认
     * @throws IllegalArgumentException 覆盖值非法（调用方转业务异常）
     */
    public static PlanTerms resolve(ProductProfile profile,
                                    Integer overrideTermCount,
                                    Integer overrideRateBp,
                                    Integer overrideBonusMonth) {
        if (profile == null) {
            throw new IllegalArgumentException("产品档位档案为空");
        }
        int termCount = overrideTermCount != null ? overrideTermCount : profile.termCount();
        int rateBp = overrideRateBp != null ? overrideRateBp : profile.rateBp();
        Integer bonusMonth = overrideBonusMonth != null ? overrideBonusMonth : profile.bonusMonth();
        validate(termCount, rateBp, bonusMonth);
        return new PlanTerms(profile.productCode(), profile.productVersion(), termCount, rateBp,
                bonusMonth, profile.waiverCapBp());
    }

    /**
     * 纯入参模式（未指定产品档位）：保持既有行为，档位码与版本为 null，
     * 但利率照样进快照——这样连「不产品化」的路径也能复现当时利率。
     */
    public static PlanTerms fromDirectInput(Integer termCount, Integer rateBp, Integer bonusMonth) {
        int t = termCount == null ? 0 : termCount;
        int r = rateBp == null ? 0 : rateBp;
        validate(t, r, bonusMonth);
        return new PlanTerms(null, null, t, r, bonusMonth, null);
    }

    private static void validate(int termCount, int rateBp, Integer bonusMonth) {
        if (termCount <= 0) {
            throw new IllegalArgumentException("期次必须为正整数");
        }
        if (rateBp < 0) {
            throw new IllegalArgumentException("月利率基点必须非负");
        }
        if (bonusMonth != null && (bonusMonth < 1 || bonusMonth > 12)) {
            throw new IllegalArgumentException("奖金月必须是 1-12");
        }
    }
}
