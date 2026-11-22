package com.company.retail.externalapi.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 调用留痕 JDBC 实现（{@code integration.call_logs}，append-only）。
 * partner_id 按 partner_code 回查，未登记 partner 记 NULL。
 */
@Component
public class JdbcCallLogStore implements CallLogStore {

    private final JdbcTemplate jdbc;

    public JdbcCallLogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String partnerCode, String endpoint, int httpStatus, long durationMs,
                       String result, String traceId) {
        jdbc.update(
                "INSERT INTO integration.call_logs "
                        + "(partner_id, direction, endpoint, http_status, duration_ms, result, trace_id) "
                        + "VALUES (?, 'IN', ?, ?, ?, ?, ?)",
                resolvePartnerIdOrNull(partnerCode), truncate(endpoint), httpStatus,
                (int) Math.min(durationMs, Integer.MAX_VALUE), truncate(result), traceId);
    }

    private Long resolvePartnerIdOrNull(String partnerCode) {
        if (partnerCode == null) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT partner_id FROM integration.partners WHERE partner_code = ?",
                Long.class, partnerCode);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String truncate(String v) {
        if (v == null) {
            return null;
        }
        return v.length() <= 128 ? v : v.substring(0, 128);
    }
}
