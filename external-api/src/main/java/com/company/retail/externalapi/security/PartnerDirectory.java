package com.company.retail.externalapi.security;

import java.util.List;
import java.util.Set;

/**
 * partner 档案目录（W9-B）：状态 / scope / IP 白名单的统一读取口。
 *
 * <p>实现二选一（{@code retail.dmz.registry-mode}）：
 * {@link ConfigPartnerDirectory}（CONFIG，W2 dev 口径）或
 * {@link JdbcPartnerDirectory}（DB，{@code integration.partners} 表真相源）。</p>
 */
public interface PartnerDirectory {

    /** 未登记 partner 返回 null（守门人按 RF_PTNR_003 拒绝）。 */
    PartnerProfile profileOf(String partnerCode);

    /**
     * @param partnerCode partner 编码
     * @param active      状态是否 ACTIVE（DB 模式读 partners.status；CONFIG 恒 true）
     * @param scopes      允许的路由键（如 echo、dms_applications）
     * @param ipAllowlist 允许的客户端 IP 列表；空 = 不限
     */
    record PartnerProfile(String partnerCode, boolean active, Set<String> scopes,
                          List<String> ipAllowlist) {
    }
}
