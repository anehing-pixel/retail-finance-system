package com.company.retail.titlerelease.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 附件登记 MyBatis Mapper（表 core.attachments，V1；ADR-RF-008：DB 只存相对路径与哈希，文件本体在本地磁盘）。
 */
public interface AttachmentMapper {

    @Insert("INSERT INTO core.attachments (biz_type, biz_id, relative_path, sha256, size_bytes, "
            + "uploaded_by, trace_id) VALUES (#{bizType}, #{bizId}, #{relativePath}, #{sha256}, "
            + "#{sizeBytes}, #{uploadedBy}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "attachmentId", keyColumn = "attachment_id")
    int insert(Map<String, Object> params);

    @Select("SELECT attachment_id, biz_type, biz_id, relative_path, sha256, size_bytes, "
            + "uploaded_by, trace_id FROM core.attachments WHERE attachment_id = #{attachmentId}")
    List<Map<String, Object>> findById(@Param("attachmentId") long attachmentId);

    /**
     * 待登记占位行幂等插入（同一 objectKey 不重复插入）。
     *
     * <p>用 {@code INSERT ... SELECT ... WHERE NOT EXISTS} 的原子语义，不用「捕获唯一键异常再重读」——
     * PostgreSQL 事务内一旦触发唯一键错误，整笔事务即被置为 aborted，无法继续读库。</p>
     */
    @Insert("INSERT INTO core.attachments (biz_type, biz_id, relative_path, sha256, size_bytes, "
            + "uploaded_by, trace_id) SELECT 'PENDING_UPLOAD', #{bizId}, #{relativePath}, #{sha256}, "
            + "#{sizeBytes}, #{uploadedBy}, #{traceId} WHERE NOT EXISTS (SELECT 1 FROM core.attachments "
            + "WHERE biz_type = 'PENDING_UPLOAD' AND biz_id = #{bizId})")
    int insertPendingIfAbsent(@Param("bizId") String bizId, @Param("relativePath") String relativePath,
                               @Param("sha256") String sha256, @Param("sizeBytes") long sizeBytes,
                               @Param("uploadedBy") String uploadedBy, @Param("traceId") String traceId);

    /** 待登记行（消费者已落盘、尚未转正）。 */
    @Select("SELECT attachment_id, biz_type, biz_id, relative_path, sha256, size_bytes, "
            + "uploaded_by, trace_id FROM core.attachments WHERE biz_type = 'PENDING_UPLOAD' "
            + "AND biz_id = #{bizId} ORDER BY attachment_id LIMIT 1")
    List<Map<String, Object>> findPending(@Param("bizId") String bizId);

    /** 已登记行（按相对路径 = objectKey）：register 幂等，避免重复登记产生第二行。 */
    @Select("SELECT attachment_id, biz_type, biz_id, relative_path, sha256, size_bytes, "
            + "uploaded_by, trace_id FROM core.attachments WHERE relative_path = #{relativePath} "
            + "AND biz_type <> 'PENDING_UPLOAD' ORDER BY attachment_id LIMIT 1")
    List<Map<String, Object>> findRegisteredByRelativePath(@Param("relativePath") String relativePath);

    /** 待登记行转正（改正式 biz_type / biz_id；主键与 relative_path 不变，attachment_id 保持不变）。 */
    @Update("UPDATE core.attachments SET biz_type = #{bizType}, biz_id = #{bizId}, updated_at = now(), "
            + "version = version + 1 WHERE biz_type = 'PENDING_UPLOAD' AND biz_id = #{pendingBizId}")
    int promotePending(@Param("pendingBizId") String pendingBizId, @Param("bizType") String bizType,
                       @Param("bizId") String bizId);
}
