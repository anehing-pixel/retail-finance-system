package com.company.retail.appservice.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 申请幂等记录 MyBatis Mapper（core.applications 的 idempotency_key / request_fingerprint，
 * R09 §6 双兜底）。
 *
 * <p>不新开表——幂等记录与业务行同生共死，天然同事务。</p>
 */
public interface IdempotencyMapper {

    /** 幂等记录行（键 + 指纹 + 受理时刻）。 */
    @Select("SELECT idempotency_key, request_fingerprint, created_at FROM core.applications "
            + "WHERE idempotency_key = #{key}")
    List<Map<String, Object>> findIdempotencyRows(@Param("key") String key);

    /** 补写请求指纹（显式补写场景）。 */
    @Update("UPDATE core.applications SET request_fingerprint = #{requestFingerprint} "
            + "WHERE idempotency_key = #{key}")
    int updateRequestFingerprint(@Param("requestFingerprint") String requestFingerprint,
                                 @Param("key") String key);

    /** 幂等键 → 申请号（重放定位，单列）。 */
    @Select("SELECT application_id FROM core.applications WHERE idempotency_key = #{key}")
    List<Long> findApplicationIdsByKey(@Param("key") String key);
}
