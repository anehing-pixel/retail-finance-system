package com.company.retail.externalapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * partner 配置档案（{@code retail.integration.partners.<code>.*}）——W2 dev 口径与
 * CONFIG 模式的 registry 来源：per-partner 密钥、scope（路由键列表）、IP 白名单。
 *
 * <p>生产用 {@code registry-mode: DB}（{@code integration.partners} 表为准）；
 * 密钥本身终将由 {@code partner_credentials.secret_ref} 指向密钥管理设施（R07 §5.3）。</p>
 */
@ConfigurationProperties(prefix = "retail.integration")
public class PartnerProperties {

    private Map<String, PartnerDef> partners;

    public Map<String, PartnerDef> getPartners() {
        return partners;
    }

    public void setPartners(Map<String, PartnerDef> partners) {
        this.partners = partners;
    }

    public static class PartnerDef {

        /** per-partner HMAC 密钥（生产走 secret_ref，不明文进配置）。 */
        private String secret;

        /** 允许的路由键（scope）；空/缺省 = 仅 echo（W2 dev 自证口径）。 */
        private List<String> scopes;

        /** 逗号分隔的 IP 白名单；空 = 不限 IP。 */
        private String ipAllowlist;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes;
        }

        public String getIpAllowlist() {
            return ipAllowlist;
        }

        public void setIpAllowlist(String ipAllowlist) {
            this.ipAllowlist = ipAllowlist;
        }
    }
}
