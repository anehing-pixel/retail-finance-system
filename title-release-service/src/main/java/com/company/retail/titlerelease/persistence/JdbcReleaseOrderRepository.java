package com.company.retail.titlerelease.persistence;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * {@code core.release_orders} / {@code core.release_documents} / {@code core.registration_receipts}
 * 持久化（V8 表 + V13 证据锚点列；权威写者 BC-8 title-release-service）。
 *
 * <p>uk_release_loan：一债权一张解除订单（消费端幂等锚点）；
 * uk_rdoc_order_type：解除文件四件套各一条。</p>
 */
@Repository
public class JdbcReleaseOrderRepository {

    public record OrderRow(long releaseOrderId, long loanId, long titleId, String status,
                           String source, Long loanSettledEventId, String externalSubmissionRef,
                           Timestamp confirmedAt, String confirmationAuthority, int version) {
    }

    public record DocumentRow(long documentId, long releaseOrderId, String docType,
                              Long attachmentId, Timestamp issuedAt, String deliveredTo) {
    }

    public record ReceiptRow(long receiptId, long releaseOrderId, String authority,
                             String receiptRef, Timestamp receivedAt, String verifiedBy, String rawRef) {
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, i) -> new OrderRow(
            rs.getLong("release_order_id"),
            rs.getLong("loan_id"),
            rs.getLong("title_id"),
            rs.getString("status"),
            rs.getString("source"),
            rs.getObject("loan_settled_event_id") == null
                    ? null : rs.getLong("loan_settled_event_id"),
            rs.getString("external_submission_ref"),
            rs.getTimestamp("confirmed_at"),
            rs.getString("confirmation_authority"),
            rs.getInt("version"));

    private static final RowMapper<DocumentRow> DOC_MAPPER = (rs, i) -> new DocumentRow(
            rs.getLong("document_id"),
            rs.getLong("release_order_id"),
            rs.getString("doc_type"),
            rs.getObject("attachment_id") == null ? null : rs.getLong("attachment_id"),
            rs.getTimestamp("issued_at"),
            rs.getString("delivered_to"));

    private static final RowMapper<ReceiptRow> RECEIPT_MAPPER = (rs, i) -> new ReceiptRow(
            rs.getLong("receipt_id"),
            rs.getLong("release_order_id"),
            rs.getString("authority"),
            rs.getString("receipt_ref"),
            rs.getTimestamp("received_at"),
            rs.getString("verified_by"),
            rs.getString("raw_ref"));

    private final JdbcTemplate jdbc;

    public JdbcReleaseOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建订单（status=CREATED；uk_release_loan 幂等）。 */
    public long insert(long loanId, long titleId, String source, Long loanSettledEventId, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO core.release_orders (loan_id, title_id, status, source, "
                                + "loan_settled_event_id, trace_id) VALUES (?, ?, 'CREATED', ?, ?, ?)",
                        new String[]{"release_order_id"});
                ps.setLong(1, loanId);
                ps.setLong(2, titleId);
                ps.setString(3, source);
                if (loanSettledEventId == null) {
                    ps.setObject(4, null);
                } else {
                    ps.setLong(4, loanSettledEventId);
                }
                ps.setString(5, traceId);
                return ps;
            }, keys);
        } catch (DuplicateKeyException e) {
            // uk_release_loan：并发重复创建由调用方按「已存在即返回既有」处理（消费幂等）
            return -1;
        }
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public Optional<OrderRow> findById(long releaseOrderId) {
        List<OrderRow> rows = jdbc.query(
                "SELECT * FROM core.release_orders WHERE release_order_id = ?", ORDER_MAPPER, releaseOrderId);
        return rows.stream().findFirst();
    }

    public Optional<OrderRow> findByLoanId(long loanId) {
        List<OrderRow> rows = jdbc.query(
                "SELECT * FROM core.release_orders WHERE loan_id = ?", ORDER_MAPPER, loanId);
        return rows.stream().findFirst();
    }

    /** 状态推进（乐观锁兜底；confirmed_at / authority / submission_ref 由调用方决定是否更新）。 */
    public int updateStatus(long releaseOrderId, String status, Timestamp confirmedAt,
                            String confirmationAuthority, String externalSubmissionRef, int version) {
        return jdbc.update(
                "UPDATE core.release_orders SET status = ?, "
                        + "confirmed_at = COALESCE(?, confirmed_at), "
                        + "confirmation_authority = COALESCE(?, confirmation_authority), "
                        + "external_submission_ref = COALESCE(?, external_submission_ref), "
                        + "version = version + 1, updated_at = now() "
                        + "WHERE release_order_id = ? AND version = ?",
                status, confirmedAt, confirmationAuthority, externalSubmissionRef,
                releaseOrderId, version);
    }

    // ---------- 解除文件四件套 ----------

    /** 登记文件（uk_rdoc_order_type；重复登记同一类型 → 更新交付留痕）。 */
    public long upsertDocument(long releaseOrderId, String docType, Long attachmentId,
                               String deliveredTo, String traceId) {
        jdbc.update(
                "INSERT INTO core.release_documents (release_order_id, doc_type, attachment_id, "
                        + "issued_at, delivered_to, trace_id) VALUES (?, ?, ?, now(), ?, ?) "
                        + "ON CONFLICT (release_order_id, doc_type) DO UPDATE SET "
                        + "delivered_to = EXCLUDED.delivered_to, issued_at = now(), updated_at = now()",
                releaseOrderId, docType, attachmentId, deliveredTo, traceId);
        Long id = jdbc.queryForObject(
                "SELECT document_id FROM core.release_documents "
                        + "WHERE release_order_id = ? AND doc_type = ?",
                Long.class, releaseOrderId, docType);
        return id == null ? 0 : id;
    }

    /** 回填附件引用（W9-C：出具环节登记 core.attachments 后落引用位；不覆盖已登记值）。 */
    public int attachDocument(long documentId, long attachmentId) {
        return jdbc.update(
                "UPDATE core.release_documents SET attachment_id = ?, updated_at = now() "
                        + "WHERE document_id = ? AND attachment_id IS NULL",
                attachmentId, documentId);
    }

    public List<DocumentRow> findDocuments(long releaseOrderId) {
        return jdbc.query(
                "SELECT * FROM core.release_documents WHERE release_order_id = ? ORDER BY document_id",
                DOC_MAPPER, releaseOrderId);
    }

    // ---------- 登记回执 ----------

    public long insertReceipt(long releaseOrderId, String authority, String receiptRef,
                              String verifiedBy, String rawRef, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO core.registration_receipts (release_order_id, authority, receipt_ref, "
                            + "verified_by, raw_ref, trace_id) VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"receipt_id"});
            ps.setLong(1, releaseOrderId);
            ps.setString(2, authority);
            ps.setString(3, receiptRef);
            ps.setString(4, verifiedBy);
            ps.setString(5, rawRef);
            ps.setString(6, traceId);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    /** 证据链⑦：存在 EXTERNAL_REGISTRY 权威回执。 */
    public boolean hasExternalReceipt(long releaseOrderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core.registration_receipts "
                        + "WHERE release_order_id = ? AND authority = 'EXTERNAL_REGISTRY'",
                Integer.class, releaseOrderId);
        return count != null && count > 0;
    }

    public List<ReceiptRow> findReceipts(long releaseOrderId) {
        return jdbc.query(
                "SELECT * FROM core.registration_receipts WHERE release_order_id = ? ORDER BY receipt_id",
                RECEIPT_MAPPER, releaseOrderId);
    }
}
