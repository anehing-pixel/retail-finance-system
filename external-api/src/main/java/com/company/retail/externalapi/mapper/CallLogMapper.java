package com.company.retail.externalapi.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * DMZ 调用留痕 MyBatis Mapper（integration.call_logs，append-only，R06 §integration）。
 *
 * <p>SQL 与改造前的 JdbcTemplate 版逐字一致：direction 固定 'IN'，result 记语义码不记 PII 明文。</p>
 */
public interface CallLogMapper {

    /** 写入一条入站调用留痕。 */
    @Insert("INSERT INTO integration.call_logs "
            + "(partner_id, direction, endpoint, http_status, duration_ms, result, trace_id) "
            + "VALUES (#{partnerId}, 'IN', #{endpoint}, #{httpStatus}, #{durationMs}, #{result}, #{traceId})")
    int insertCallLog(@Param("partnerId") Long partnerId,
                      @Param("endpoint") String endpoint,
                      @Param("httpStatus") int httpStatus,
                      @Param("durationMs") int durationMs,
                      @Param("result") String result,
                      @Param("traceId") String traceId);
}
