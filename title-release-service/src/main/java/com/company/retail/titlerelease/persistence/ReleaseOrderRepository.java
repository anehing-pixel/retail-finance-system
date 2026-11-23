package com.company.retail.titlerelease.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.titlerelease.mapper.ReleaseOrderMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code core.release_orders} / {@code core.release_documents} / {@code core.registration_receipts}
 * 持久化（V8 表 + V13 证据锚点列；权威写者 BC-8 title-release-service）。
 *
 * <p>uk_release_loan：一债权一张解除订单（消费端幂等锚点）；
 * uk_rdoc_order_type：解除文件四件套各一条。</p>
 */
@Repository
public class ReleaseOrderRepository {

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

    private final ReleaseOrderMapper mapper;

    public ReleaseOrderRepository(ReleaseOrderMapper mapper) {
        this.mapper = mapper;
    }

    /** 创建订单（status=CREATED；uk_release_loan 幂等）。 */
    public long insert(long loanId, long titleId, String source, Long loanSettledEventId, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("loanId", loanId);
        params.put("titleId", titleId);
        params.put("source", source);
        params.put("loanSettledEventId", loanSettledEventId);
        params.put("traceId", traceId);
        try {
            mapper.insertOrder(params);
        } catch (DuplicateKeyException e) {
            // uk_release_loan：并发重复创建由调用方按「已存在即返回既有」处理（消费幂等）
            return -1;
        }
        Object id = params.get("releaseOrderId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    public Optional<OrderRow> findById(long releaseOrderId) {
        List<Map<String, Object>> rows = mapper.findOrderById(releaseOrderId);
        return rows.stream().findFirst().map(ReleaseOrderRepository::toOrderRow);
    }

    public Optional<OrderRow> findByLoanId(long loanId) {
        List<Map<String, Object>> rows = mapper.findOrderByLoanId(loanId);
        return rows.stream().findFirst().map(ReleaseOrderRepository::toOrderRow);
    }

    /** 状态推进（乐观锁兜底；confirmed_at / authority / submission_ref 由调用方决定是否更新）。 */
    public int updateStatus(long releaseOrderId, String status, Timestamp confirmedAt,
                            String confirmationAuthority, String externalSubmissionRef, int version) {
        return mapper.updateOrderStatus(releaseOrderId, status, confirmedAt, confirmationAuthority,
                externalSubmissionRef, version);
    }

    // ---------- 解除文件四件套 ----------

    /** 登记文件（uk_rdoc_order_type；重复登记同一类型 → 更新交付留痕）。 */
    public long upsertDocument(long releaseOrderId, String docType, Long attachmentId,
                               String deliveredTo, String traceId) {
        mapper.upsertDocument(releaseOrderId, docType, attachmentId, deliveredTo, traceId);
        List<Long> ids = mapper.findDocumentId(releaseOrderId, docType);
        return ids.isEmpty() ? 0 : ids.get(0);
    }

    /** 回填附件引用（W9-C：出具环节登记 core.attachments 后落引用位；不覆盖已登记值）。 */
    public int attachDocument(long documentId, long attachmentId) {
        return mapper.attachDocument(documentId, attachmentId);
    }

    public List<DocumentRow> findDocuments(long releaseOrderId) {
        List<Map<String, Object>> rows = mapper.findDocuments(releaseOrderId);
        return rows.stream().map(ReleaseOrderRepository::toDocumentRow).toList();
    }

    // ---------- 登记回执 ----------

    public long insertReceipt(long releaseOrderId, String authority, String receiptRef,
                              String verifiedBy, String rawRef, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("releaseOrderId", releaseOrderId);
        params.put("authority", authority);
        params.put("receiptRef", receiptRef);
        params.put("verifiedBy", verifiedBy);
        params.put("rawRef", rawRef);
        params.put("traceId", traceId);
        mapper.insertReceipt(params);
        Object id = params.get("receiptId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    /** 证据链⑦：存在 EXTERNAL_REGISTRY 权威回执。 */
    public boolean hasExternalReceipt(long releaseOrderId) {
        return mapper.countExternalReceipts(releaseOrderId) > 0;
    }

    public List<ReceiptRow> findReceipts(long releaseOrderId) {
        List<Map<String, Object>> rows = mapper.findReceipts(releaseOrderId);
        return rows.stream().map(ReleaseOrderRepository::toReceiptRow).toList();
    }

    private static OrderRow toOrderRow(Map<String, Object> row) {
        return new OrderRow(
                RowValues.asLong(row, "release_order_id"),
                RowValues.asLong(row, "loan_id"),
                RowValues.asLong(row, "title_id"),
                RowValues.asString(row, "status"),
                RowValues.asString(row, "source"),
                RowValues.asLongOrNull(row, "loan_settled_event_id"),
                RowValues.asString(row, "external_submission_ref"),
                RowValues.asTimestamp(row, "confirmed_at"),
                RowValues.asString(row, "confirmation_authority"),
                RowValues.asInt(row, "version"));
    }

    private static DocumentRow toDocumentRow(Map<String, Object> row) {
        return new DocumentRow(
                RowValues.asLong(row, "document_id"),
                RowValues.asLong(row, "release_order_id"),
                RowValues.asString(row, "doc_type"),
                RowValues.asLongOrNull(row, "attachment_id"),
                RowValues.asTimestamp(row, "issued_at"),
                RowValues.asString(row, "delivered_to"));
    }

    private static ReceiptRow toReceiptRow(Map<String, Object> row) {
        return new ReceiptRow(
                RowValues.asLong(row, "receipt_id"),
                RowValues.asLong(row, "release_order_id"),
                RowValues.asString(row, "authority"),
                RowValues.asString(row, "receipt_ref"),
                RowValues.asTimestamp(row, "received_at"),
                RowValues.asString(row, "verified_by"),
                RowValues.asString(row, "raw_ref"));
    }
}
