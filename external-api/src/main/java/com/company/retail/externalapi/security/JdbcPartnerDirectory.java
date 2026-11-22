package com.company.retail.externalapi.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DB 模式 partner 档案：生产真相源 {@code integration.partners}
 * （status / scope JSONB / ip_allowlist，R06 §integration）。
 *
 * <p>scope JSONB 语义：数组 = 允许的路由键列表（如 {@code ["echo"]}）；
 * 对象 = 特性开关（取值为 true 的键，兼容 V9 种子布尔图）；其他/解析失败一律空集
 * （fail-closed，拒之 403）。ip_allowlist 为逗号分隔 IP 文本。</p>
 */
/** 装配由 {@code ExternalApiConfig#partnerDirectory} 按 registry-mode 提供，不注册为组件。 */
public class JdbcPartnerDirectory implements PartnerDirectory {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcPartnerDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PartnerProfile profileOf(String partnerCode) {
        if (partnerCode == null) {
            return null;
        }
        List<Object[]> rows = jdbc.query(
                "SELECT status, scope, ip_allowlist FROM integration.partners WHERE partner_code = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                partnerCode);
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.get(0);
        boolean active = "ACTIVE".equalsIgnoreCase(String.valueOf(row[0]));
        return new PartnerProfile(partnerCode, active, parseScope(row[1]),
                ConfigPartnerDirectory.parseAllowlist((String) row[2]));
    }

    private Set<String> parseScope(Object scopeJson) {
        if (scopeJson == null || String.valueOf(scopeJson).isBlank()) {
            return Set.of();
        }
        try {
            JsonNode node = mapper.readTree(String.valueOf(scopeJson));
            if (node.isArray()) {
                Set<String> keys = new java.util.HashSet<>();
                node.forEach(n -> keys.add(n.asText()));
                return Set.copyOf(keys);
            }
            if (node.isObject()) {
                Set<String> keys = new java.util.HashSet<>();
                node.fields().forEachRemaining(e -> {
                    if (e.getValue().asBoolean(false)) {
                        keys.add(e.getKey());
                    }
                });
                return Set.copyOf(keys);
            }
            if (node.isTextual()) {
                return Set.of(node.asText());
            }
            return Set.of();
        } catch (Exception e) {
            return Set.of();
        }
    }
}
