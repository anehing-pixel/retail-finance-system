package com.company.retail.core.domain;

/**
 * 还款计划的生效参数口径（<b>快照</b>）。
 *
 * <p>生成计划时落库的就是它：产品档位码 + 档位版本 + 实际采用的期数与利率。
 * 只存 {@code productCode} 引用是不够的——配置会改会下架，
 * 改了配置不得改历史计划，所以实际用值必须一并快照。</p>
 *
 * @param productCode    产品档位码；纯入参模式（未指定档位）为 null
 * @param productVersion 档位业务版本；纯入参模式为 null
 * @param termCount      生效期数
 * @param rateBp         生效月利率基点（200 = 2.0%/月；0 = 0 息）
 * @param bonusMonth     生效奖金月（1-12）；null = 不支持
 * @param waiverCapBp    提前结清减免上限（千分比）；null = 不设上限（待法务核实）
 */
public record PlanTerms(String productCode,
                        Integer productVersion,
                        int termCount,
                        int rateBp,
                        Integer bonusMonth,
                        Integer waiverCapBp) {
}
