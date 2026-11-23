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
}
