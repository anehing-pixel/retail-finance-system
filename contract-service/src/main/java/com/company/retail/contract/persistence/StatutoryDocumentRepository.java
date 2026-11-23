package com.company.retail.contract.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.contract.domain.StatutoryDocument;
import com.company.retail.contract.mapper.StatutoryDocumentMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code core.statutory_documents} 持久化（MyBatis 实现；V5 表；append-only 留痕，不物理删除）。
 */
@Repository
public class StatutoryDocumentRepository {

    private final StatutoryDocumentMapper mapper;

    public StatutoryDocumentRepository(StatutoryDocumentMapper mapper) {
        this.mapper = mapper;
    }

    public long insertDelivery(StatutoryDocument document, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("contractId", document.contractId());
        params.put("docType", document.docType());
        params.put("templateVersion", document.templateVersion());
        params.put("deliveredAt", Timestamp.from(document.deliveredAt() == null
                ? Instant.now() : document.deliveredAt()));
        params.put("deliveryMethod", document.deliveryMethod());
        params.put("attachmentId", document.attachmentId());
        params.put("traceId", traceId);
        // 主键由驱动回填到 params 的 docId 键（原实现用 GeneratedKeyHolder 取值，语义等价）
        mapper.insertDelivery(params);
        Object docId = params.get("docId");
        return docId == null ? 0 : ((Number) docId).longValue();
    }

    public List<StatutoryDocument> findByContract(long contractId) {
        return mapper.findByContract(contractId).stream()
                .map(StatutoryDocumentRepository::toDocument)
                .toList();
    }

    /** 行 → 交付留痕（列名小写下划线，读取口径与原 RowMapper 一致）。 */
    private static StatutoryDocument toDocument(Map<String, Object> row) {
        return new StatutoryDocument(
                RowValues.asLong(row, "doc_id"),
                RowValues.asLong(row, "contract_id"),
                RowValues.asString(row, "doc_type"),
                RowValues.asString(row, "template_version"),
                instant(row, "delivered_at"),
                RowValues.asString(row, "delivery_method"),
                RowValues.asLongOrNull(row, "attachment_id"),
                instant(row, "acknowledged_at"));
    }

    /** TIMESTAMPTZ → Instant（对齐原 {@code rs.getTimestamp(...).toInstant()}）。 */
    private static Instant instant(Map<String, Object> row, String col) {
        Timestamp timestamp = RowValues.asTimestamp(row, col);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
