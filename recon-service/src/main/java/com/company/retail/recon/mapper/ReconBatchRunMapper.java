package com.company.retail.recon.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 批处理留痕 MyBatis Mapper（表 core.batch_runs；uk_batch_job_date 同日重跑复用同一行，A28）。
 */
public interface ReconBatchRunMapper {

    @Insert("INSERT INTO core.batch_runs (job_name, biz_date, status, trace_id) "
            + "VALUES (#{jobName}, #{bizDate}, 'RUNNING', #{traceId}) "
            + "ON CONFLICT (job_name, biz_date) DO UPDATE "
            + "SET status = 'RUNNING', started_at = now(), finished_at = NULL, "
            + "    trace_id = EXCLUDED.trace_id")
    int start(@Param("jobName") String jobName,
              @Param("bizDate") LocalDate bizDate,
              @Param("traceId") String traceId);

    @Update("UPDATE core.batch_runs SET status = #{status}, finished_at = now() "
            + "WHERE job_name = #{jobName} AND biz_date = #{bizDate}")
    int finish(@Param("jobName") String jobName,
               @Param("bizDate") LocalDate bizDate,
               @Param("status") String status);

    @Select("<script>"
            + "SELECT run_id, job_name, biz_date, status FROM core.batch_runs"
            + "<if test=\"jobNameOrNull != null and jobNameOrNull.trim().length() > 0\">"
            + " WHERE job_name = #{jobNameOrNull}"
            + "</if>"
            + " ORDER BY run_id DESC LIMIT 100"
            + "</script>")
    List<Map<String, Object>> list(@Param("jobNameOrNull") String jobNameOrNull);
}
