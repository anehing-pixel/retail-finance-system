package com.company.retail.contract.persistence;

import com.company.retail.contract.domain.StatutoryDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

/**
 * {@code core.statutory_documents} 持久化（V5 表；append-only 留痕，不物理删除）。
 */
@Repository
public class JdbcStatutoryDocumentRepository {

    private static final RowMapper<StatutoryDocument> MAPPER = (rs, i) -> new StatutoryDocument(
            rs.getLong("doc_id"),
            rs.getLong("contract_id"),
            rs.getString("doc_type"),
            rs.getString("template_version"),
            rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant(),
            rs.getString("delivery_method"),
            rs.getObject("attachment_id") == null ? null : rs.getLong("attachment_id"),
            rs.getTimestamp("acknowledged_at") == null ? null : rs.getTimestamp("acknowledged_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcStatutoryDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertDelivery(StatutoryDocument document, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO core.statutory_documents (contract_id, doc_type, template_version, "
                            + "delivered_at, delivery_method, attachment_id, trace_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"doc_id"});
            ps.setLong(1, document.contractId());
            ps.setString(2, document.docType());
            ps.setString(3, document.templateVersion());
            ps.setTimestamp(4, document.deliveredAt() == null ? Timestamp.from(java.time.Instant.now())
                    : Timestamp.from(document.deliveredAt()));
            ps.setString(5, document.deliveryMethod());
            if (document.attachmentId() == null) {
                ps.setObject(6, null);
            } else {
                ps.setLong(6, document.attachmentId());
            }
            ps.setString(7, traceId);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public List<StatutoryDocument> findByContract(long contractId) {
        return jdbc.query(
                "SELECT * FROM core.statutory_documents WHERE contract_id = ? ORDER BY doc_id", MAPPER, contractId);
    }
}
