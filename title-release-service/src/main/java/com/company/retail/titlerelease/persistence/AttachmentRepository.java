package com.company.retail.titlerelease.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.titlerelease.mapper.AttachmentMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code core.attachments} 持久化（V1 表；ADR-RF-008：DB 只存相对路径与哈希，文件本体在本地磁盘）。
 *
 * <p>解押文件出具（RELEASE_DOCUMENTS_ISSUED 环节）登记：biz_type=biz_id=release_order 关联。</p>
 */
@Repository
public class AttachmentRepository {

    public record AttachmentRow(long attachmentId, String bizType, String bizId, String relativePath,
                                String sha256, long sizeBytes, String uploadedBy, String traceId) {
    }

    private final AttachmentMapper mapper;

    public AttachmentRepository(AttachmentMapper mapper) {
        this.mapper = mapper;
    }

    /** 登记附件（文件已由存储层写入并返回哈希）。 */
    public long insert(String bizType, String bizId, String relativePath, String sha256,
                       long sizeBytes, String uploadedBy, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bizType", bizType);
        params.put("bizId", bizId);
        params.put("relativePath", relativePath);
        params.put("sha256", sha256);
        params.put("sizeBytes", sizeBytes);
        params.put("uploadedBy", uploadedBy);
        params.put("traceId", traceId);
        mapper.insert(params);
        Object id = params.get("attachmentId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    public Optional<AttachmentRow> findById(long attachmentId) {
        List<Map<String, Object>> rows = mapper.findById(attachmentId);
        return rows.stream().findFirst().map(AttachmentRepository::toRow);
    }

    private static AttachmentRow toRow(Map<String, Object> row) {
        return new AttachmentRow(
                RowValues.asLong(row, "attachment_id"),
                RowValues.asString(row, "biz_type"),
                RowValues.asString(row, "biz_id"),
                RowValues.asString(row, "relative_path"),
                RowValues.asString(row, "sha256"),
                RowValues.asLong(row, "size_bytes"),
                RowValues.asString(row, "uploaded_by"),
                RowValues.asString(row, "trace_id"));
    }

    /**
     * 待登记占位行的 biz_type（占位行复用现有 core.attachments，不改表结构）。
     */
    public static final String PENDING_BIZ_TYPE = "PENDING_UPLOAD";

    /**
     * 幂等登记待登记行（同一 objectKey 不重复插入）；返回本次是否新插入。
     */
    public boolean insertPendingIfAbsent(String objectKey, String sha256, long sizeBytes,
                                          String uploadedBy, String traceId) {
        return mapper.insertPendingIfAbsent(objectKey, objectKey, sha256, sizeBytes,
                uploadedBy, traceId) > 0;
    }

    /** 待登记行（消费者已落盘、尚未正式登记）。 */
    public Optional<AttachmentRow> findPending(String objectKey) {
        return mapper.findPending(objectKey).stream().findFirst().map(AttachmentRepository::toRow);
    }

    /** 已登记行（按相对路径 = objectKey）：register 幂等用。 */
    public Optional<AttachmentRow> findRegisteredByRelativePath(String relativePath) {
        return mapper.findRegisteredByRelativePath(relativePath).stream().findFirst()
                .map(AttachmentRepository::toRow);
    }

    /** 待登记行转正（改正式 biz_type / biz_id）；返回受影响行数。 */
    public boolean promotePending(String objectKey, String bizType, String bizId) {
        return mapper.promotePending(objectKey, bizType, bizId) > 0;
    }
}
