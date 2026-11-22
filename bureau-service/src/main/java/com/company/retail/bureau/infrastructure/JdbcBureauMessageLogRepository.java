package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.application.BureauMessageLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 机构报文留痕 JDBC 仓储（integration.bureau_message_logs，V11 增量表，append-only）。
 *
 * <p>报文原文只在留痕表与排障查询中出现；不进事件 payload、不进应用日志（PII-4）。</p>
 */
public class JdbcBureauMessageLogRepository implements BureauMessageLogRepository {

    private final JdbcTemplate jdbc;

    public JdbcBureauMessageLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(String direction, String bureau, String bizRef, String messageText, String traceId) {
        jdbc.update("INSERT INTO integration.bureau_message_logs "
                        + "(direction, bureau, biz_ref, message_text, trace_id) VALUES (?, ?, ?, ?, ?)",
                direction, bureau, bizRef, messageText, traceId);
    }

    @Override
    public long countByBizRef(String bizRef) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration.bureau_message_logs WHERE biz_ref = ?",
                Integer.class, bizRef);
        return count == null ? 0 : count;
    }
}
