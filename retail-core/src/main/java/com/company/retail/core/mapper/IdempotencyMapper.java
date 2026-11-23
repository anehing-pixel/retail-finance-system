package com.company.retail.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 幂等记录 MyBatis Mapper（表 core.idempotency_records，V12 迁移）。
 *
 * <p>唯一约束兜底并发插入（R00 §5 幂等口径：写接口 Idempotency-Key + DB 唯一索引双兜底）；
 * response_body 保存首次成功结果，重放场景原样返回。</p>
 */
public interface IdempotencyMapper {

    @Select("SELECT idempotency_key, request_fingerprint, created_at FROM core.idempotency_records "
            + "WHERE idempotency_key = #{key}")
    List<Map<String, Object>> findRecord(@Param("key") String key);

    @Insert("INSERT INTO core.idempotency_records (idempotency_key, request_fingerprint, created_at) "
            + "VALUES (#{key}, #{fingerprint}, #{createdAt})")
    int insertRecord(@Param("key") String key, @Param("fingerprint") String fingerprint,
                     @Param("createdAt") java.sql.Timestamp createdAt);

    @Update("UPDATE core.idempotency_records SET response_body = #{responseBody} "
            + "WHERE idempotency_key = #{key}")
    int updateResponse(@Param("key") String key, @Param("responseBody") String responseBody);

    @Select("SELECT response_body FROM core.idempotency_records WHERE idempotency_key = #{key} "
            + "AND response_body IS NOT NULL")
    List<String> findResponse(@Param("key") String key);
}
