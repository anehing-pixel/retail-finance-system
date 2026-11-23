package com.company.retail.recon.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 对拍差异清单 MyBatis Mapper（表 core.recon_diffs，V14；不物理删除，状态表达生命周期）。
 *
 * <p>uk_recon_diff_open 为部分唯一索引 {@code (inv_code, entity_type, entity_id) WHERE status = 'OPEN'}，
 * 同址重跑走「插入冲突 → 原位更新明细」两段式（uk_recon_diff_open 语义，A28）。</p>
 */
public interface ReconDiffMapper {

    @Insert("INSERT INTO core.recon_diffs "
            + "(job_name, biz_date, inv_code, entity_type, entity_id, detail, status, trace_id) "
            + "VALUES (#{jobName}, #{bizDate}, #{invCode}, #{entityType}, #{entityId}, "
            + "#{detail}::jsonb, 'OPEN', #{traceId})")
    int insert(@Param("jobName") String jobName,
               @Param("bizDate") LocalDate bizDate,
               @Param("invCode") String invCode,
               @Param("entityType") String entityType,
               @Param("entityId") String entityId,
               @Param("detail") String detail,
               @Param("traceId") String traceId);

    /** 冲突后原位更新明细（重跑不产生第二份数据）。 */
    @Update("UPDATE core.recon_diffs SET detail = #{detail}::jsonb, job_name = #{jobName}, "
            + "biz_date = #{bizDate}, trace_id = #{traceId}, updated_at = now(), version = version + 1 "
            + "WHERE inv_code = #{invCode} AND entity_type = #{entityType} "
            + "AND entity_id = #{entityId} AND status = 'OPEN'")
    int updateDetail(@Param("jobName") String jobName,
                     @Param("bizDate") LocalDate bizDate,
                     @Param("invCode") String invCode,
                     @Param("entityType") String entityType,
                     @Param("entityId") String entityId,
                     @Param("detail") String detail,
                     @Param("traceId") String traceId);

    @Select("SELECT inv_code, entity_type, entity_id FROM core.recon_diffs WHERE status = 'OPEN'")
    List<Map<String, Object>> listOpenKeys();

    @Update("UPDATE core.recon_diffs SET status = 'RESOLVED', trace_id = #{traceId}, "
            + "updated_at = now(), version = version + 1 "
            + "WHERE inv_code = #{invCode} AND entity_type = #{entityType} "
            + "AND entity_id = #{entityId} AND status = 'OPEN'")
    int resolve(@Param("invCode") String invCode,
                @Param("entityType") String entityType,
                @Param("entityId") String entityId,
                @Param("traceId") String traceId);

    @Select("<script>"
            + "SELECT diff_id, job_name, biz_date, inv_code, entity_type, entity_id, detail, status "
            + "FROM core.recon_diffs WHERE 1 = 1"
            + "<if test=\"invCodeOrNull != null and invCodeOrNull.trim().length() > 0\">"
            + " AND inv_code = #{invCodeOrNull}"
            + "</if>"
            + "<if test=\"statusOrNull != null and statusOrNull.trim().length() > 0\">"
            + " AND status = #{statusOrNull}"
            + "</if>"
            + " ORDER BY diff_id DESC LIMIT 200"
            + "</script>")
    List<Map<String, Object>> list(@Param("invCodeOrNull") String invCodeOrNull,
                                   @Param("statusOrNull") String statusOrNull);

    @Select("SELECT diff_id, job_name, biz_date, inv_code, entity_type, entity_id, detail, status "
            + "FROM core.recon_diffs WHERE diff_id = #{diffId}")
    List<Map<String, Object>> findById(@Param("diffId") long diffId);
}
