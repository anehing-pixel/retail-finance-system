package com.company.retail.titlerelease.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
