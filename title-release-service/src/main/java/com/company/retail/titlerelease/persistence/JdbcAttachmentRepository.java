package com.company.retail.titlerelease.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@code core.attachments} 持久化（V1 表；ADR-RF-008：DB 只存相对路径与哈希，文件本体在本地磁盘）。
 *
 * <p>解押文件出具（RELEASE_DOCUMENTS_ISSUED 环节）登记：biz_type=biz_id=release_order 关联。</p>
 */
@Repository
public class JdbcAttachmentRepository {

    public record AttachmentRow(long attachmentId, String bizType, String bizId, String relativePath,
                                String sha256, long sizeBytes, String uploadedBy, String traceId) {
    }

    private static final RowMapper<AttachmentRow> MAPPER = (rs, i) -> new AttachmentRow(
            rs.getLong("attachment_id"),
            rs.getString("biz_type"),
            rs.getString("biz_id"),
            rs.getString("relative_path"),
            rs.getString("sha256"),
            rs.getLong("size_bytes"),
            rs.getString("uploaded_by"),
            rs.getString("trace_id"));

    private final JdbcTemplate jdbc;

    public JdbcAttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记附件（文件已由存储层写入并返回哈希）。 */
    public long insert(String bizType, String bizId, String relativePath, String sha256,
                       long sizeBytes, String uploadedBy, String traceId) {
        Long id = jdbc.queryForObject(
                "INSERT INTO core.attachments (biz_type, biz_id, relative_path, sha256, size_bytes, "
                        + "uploaded_by, trace_id) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING attachment_id",
                Long.class, bizType, bizId, relativePath, sha256, sizeBytes, uploadedBy, traceId);
        return id == null ? 0 : id;
    }

    public Optional<AttachmentRow> findById(long attachmentId) {
        List<AttachmentRow> rows = jdbc.query(
                "SELECT * FROM core.attachments WHERE attachment_id = ?", MAPPER, attachmentId);
        return rows.stream().findFirst();
    }
}
