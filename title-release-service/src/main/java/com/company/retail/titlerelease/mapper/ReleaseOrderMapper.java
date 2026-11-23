package com.company.retail.titlerelease.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 解除订单 / 解除文件 / 登记回执 MyBatis Mapper
 * （表 core.release_orders / core.release_documents / core.registration_receipts，V8 + V13 证据锚点列）。
 *
 * <p>uk_release_loan：一债权一张解除订单（消费端幂等锚点）；
 * uk_rdoc_order_type：解除文件四件套各一条。</p>
 */
public interface ReleaseOrderMapper {

    @Insert("INSERT INTO core.release_orders (loan_id, title_id, status, source, "
            + "loan_settled_event_id, trace_id) "
            + "VALUES (#{loanId}, #{titleId}, 'CREATED', #{source}, #{loanSettledEventId}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "releaseOrderId", keyColumn = "release_order_id")
    int insertOrder(Map<String, Object> params);

    @Select("SELECT release_order_id, loan_id, title_id, status, source, loan_settled_event_id, "
            + "external_submission_ref, confirmed_at, confirmation_authority, version "
            + "FROM core.release_orders WHERE release_order_id = #{releaseOrderId}")
    List<Map<String, Object>> findOrderById(@Param("releaseOrderId") long releaseOrderId);

    @Select("SELECT release_order_id, loan_id, title_id, status, source, loan_settled_event_id, "
            + "external_submission_ref, confirmed_at, confirmation_authority, version "
            + "FROM core.release_orders WHERE loan_id = #{loanId}")
    List<Map<String, Object>> findOrderByLoanId(@Param("loanId") long loanId);

    /** 状态推进（乐观锁兜底；confirmed_at / authority / submission_ref 由调用方决定是否更新）。 */
    @Update("UPDATE core.release_orders SET status = #{status}, "
            + "confirmed_at = COALESCE(#{confirmedAt}, confirmed_at), "
            + "confirmation_authority = COALESCE(#{confirmationAuthority}, confirmation_authority), "
            + "external_submission_ref = COALESCE(#{externalSubmissionRef}, external_submission_ref), "
            + "version = version + 1, updated_at = now() "
            + "WHERE release_order_id = #{releaseOrderId} AND version = #{version}")
    int updateOrderStatus(@Param("releaseOrderId") long releaseOrderId, @Param("status") String status,
                           @Param("confirmedAt") Timestamp confirmedAt,
                           @Param("confirmationAuthority") String confirmationAuthority,
                           @Param("externalSubmissionRef") String externalSubmissionRef,
                           @Param("version") int version);

    /** 登记文件（uk_rdoc_order_type；重复登记同一类型 → 更新交付留痕）。 */
    @Insert("INSERT INTO core.release_documents (release_order_id, doc_type, attachment_id, "
            + "issued_at, delivered_to, trace_id) VALUES (#{releaseOrderId}, #{docType}, #{attachmentId}, "
            + "now(), #{deliveredTo}, #{traceId}) "
            + "ON CONFLICT (release_order_id, doc_type) DO UPDATE SET "
            + "delivered_to = EXCLUDED.delivered_to, issued_at = now(), updated_at = now()")
    int upsertDocument(@Param("releaseOrderId") long releaseOrderId, @Param("docType") String docType,
                       @Param("attachmentId") Long attachmentId, @Param("deliveredTo") String deliveredTo,
                       @Param("traceId") String traceId);

    @Select("SELECT document_id FROM core.release_documents "
            + "WHERE release_order_id = #{releaseOrderId} AND doc_type = #{docType}")
    List<Long> findDocumentId(@Param("releaseOrderId") long releaseOrderId, @Param("docType") String docType);

    /** 回填附件引用（W9-C：出具环节登记 core.attachments 后落引用位；不覆盖已登记值）。 */
    @Update("UPDATE core.release_documents SET attachment_id = #{attachmentId}, updated_at = now() "
            + "WHERE document_id = #{documentId} AND attachment_id IS NULL")
    int attachDocument(@Param("documentId") long documentId, @Param("attachmentId") long attachmentId);

    @Select("SELECT document_id, release_order_id, doc_type, attachment_id, issued_at, delivered_to "
            + "FROM core.release_documents WHERE release_order_id = #{releaseOrderId} ORDER BY document_id")
    List<Map<String, Object>> findDocuments(@Param("releaseOrderId") long releaseOrderId);

    @Insert("INSERT INTO core.registration_receipts (release_order_id, authority, receipt_ref, "
            + "verified_by, raw_ref, trace_id) "
            + "VALUES (#{releaseOrderId}, #{authority}, #{receiptRef}, #{verifiedBy}, #{rawRef}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "receiptId", keyColumn = "receipt_id")
    int insertReceipt(Map<String, Object> params);

    /** 证据链⑦：存在 EXTERNAL_REGISTRY 权威回执。 */
    @Select("SELECT COUNT(*) FROM core.registration_receipts "
            + "WHERE release_order_id = #{releaseOrderId} AND authority = 'EXTERNAL_REGISTRY'")
    int countExternalReceipts(@Param("releaseOrderId") long releaseOrderId);

    @Select("SELECT receipt_id, release_order_id, authority, receipt_ref, received_at, verified_by, raw_ref "
            + "FROM core.registration_receipts WHERE release_order_id = #{releaseOrderId} ORDER BY receipt_id")
    List<Map<String, Object>> findReceipts(@Param("releaseOrderId") long releaseOrderId);
}
