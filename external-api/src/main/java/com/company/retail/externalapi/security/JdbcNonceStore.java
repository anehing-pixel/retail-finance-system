package com.company.retail.externalapi.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * nonce 存储的 JDBC 实现（{@code integration.nonces}）。
 *
 * <p>partner_id 按 partner_code 回查 {@code integration.partners}（无行则 0，
 * CONFIG 模式 dev partner 亦能落库防重放——nonce 唯一键本就按 partner 隔离）。</p>
 */
@Component
public class JdbcNonceStore implements NonceStore {

    private final JdbcTemplate jdbc;

    public JdbcNonceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryRecord(String partnerCode, String nonce) {
        return jdbc.update(
                "INSERT INTO integration.nonces (nonce, partner_id) VALUES (?, ?) "
                        + "ON CONFLICT DO NOTHING",
                nonce, resolvePartnerId(partnerCode)) == 1;
    }

    @Override
    public int purgeBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM integration.nonces WHERE used_at < ?",
                Timestamp.from(cutoff));
    }

    private long resolvePartnerId(String partnerCode) {
        List<Long> ids = jdbc.queryForList(
                "SELECT partner_id FROM integration.partners WHERE partner_code = ?",
                Long.class, partnerCode);
        return ids.isEmpty() ? 0L : ids.get(0);
    }
}
