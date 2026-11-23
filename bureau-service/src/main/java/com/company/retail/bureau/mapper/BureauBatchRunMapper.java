package com.company.retail.bureau.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 批处理留痕 MyBatis Mapper（表 core.batch_runs；uk_batch_job_date 同日重跑复用同一行，A28）。
 *
 * <p>机制与 servicing 的批处理留痕同构；bureau 自持一份（语义层隔离，ADR-RF-003），
 * 不跨模块引用 servicing 代码。</p>
 */
public interface BureauBatchRunMapper {

    /** 开始一轮：同 job+bizDate 重跑 → 复位 RUNNING，不留第二行。 */
    @Insert("INSERT INTO core.batch_runs (job_name, biz_date, status, trace_id) "
            + "VALUES (#{jobName}, #{bizDate}, 'RUNNING', #{traceId}) "
            + "ON CONFLICT (job_name, biz_date) DO UPDATE "
            + "SET status = 'RUNNING', started_at = now(), finished_at = NULL, "
            + "    trace_id = EXCLUDED.trace_id")
    int start(@Param("jobName") String jobName,
              @Param("bizDate") LocalDate bizDate,
              @Param("traceId") String traceId);

    /** 结束（SUCCESS / FAILED + finished_at）。 */
    @Update("UPDATE core.batch_runs SET status = #{status}, finished_at = now() "
            + "WHERE job_name = #{jobName} AND biz_date = #{bizDate}")
    int finish(@Param("jobName") String jobName,
               @Param("bizDate") LocalDate bizDate,
               @Param("status") String status);
}
