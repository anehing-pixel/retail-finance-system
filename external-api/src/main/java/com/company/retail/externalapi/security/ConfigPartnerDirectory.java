package com.company.retail.externalapi.security;

import com.company.retail.externalapi.config.PartnerProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CONFIG 模式 partner 档案（W2 dev 口径延续）：状态恒 ACTIVE、scope/IP 来自配置。
 *
 * <p>scope 缺省只放行 {@code echo}——W2「验签通过即允许 /external/echo 自证」的
 * 既有语义原样保留，常驻栈 smoke_w2.py 不受影响。</p>
 */
/** 装配由 {@code ExternalApiConfig#partnerDirectory} 按 registry-mode 提供，不注册为组件。 */
public class ConfigPartnerDirectory implements PartnerDirectory {

    static final String DEFAULT_SCOPE = "echo";

    private final PartnerProperties properties;

    public ConfigPartnerDirectory(PartnerProperties properties) {
        this.properties = properties;
    }

    @Override
    public PartnerProfile profileOf(String partnerCode) {
        if (partnerCode == null || properties.getPartners() == null) {
            return null;
        }
        PartnerProperties.PartnerDef def = properties.getPartners().get(partnerCode);
        if (def == null) {
            return null;
        }
        Set<String> scopes = (def.getScopes() == null || def.getScopes().isEmpty())
                ? Set.of(DEFAULT_SCOPE)
                : Set.copyOf(def.getScopes());
        return new PartnerProfile(partnerCode, true, scopes, parseAllowlist(def.getIpAllowlist()));
    }

    static List<String> parseAllowlist(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
