package com.company.retail.core.domain;

import java.time.LocalDate;

/**
 * 产品档位档案（不可变快照）。
 *
 * <p>本 record 是<b>缓存里存的东西</b>，因此必须不可变：并发改写共享的可变对象会污染全局，
 * 而金融配置一旦被污染，错误会沿「按错误利率生成的计划」扩散到账务与报送。</p>
 *
 * <p><b>只定义还款口径</b>：期数、月利率基点、允许的奖金月、提前结清减免上限。
 * 费用与费率属合同条款（以合同侧口径为准），不进产品档位。</p>
 *
 * <p><b>版本化</b>：同一 {@code productCode} 可有多行，各自 {@code productVersion} 与生效区间；
 * 「当前生效档位」= 生效日 <= 业务日 < 失效日 且启用，按生效日倒序取一版。
 * 改配置 = 旧版本封口停用 + 新版本插入（不原地覆盖，保住变更审计）。</p>
 */
public record ProductProfile(String productCode,
                             int productVersion,
                             String productName,
                             String scheme,
                             int termCount,
                             int rateBp,
                             Integer bonusMonth,
                             Integer waiverCapBp,
                             LocalDate effectiveFrom,
                             LocalDate effectiveTo,
                             boolean enabled,
                             int lockVersion) {
}
