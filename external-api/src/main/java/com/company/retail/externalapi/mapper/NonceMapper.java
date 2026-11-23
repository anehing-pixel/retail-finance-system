package com.company.retail.externalapi.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * nonce 防重放 MyBatis Mapper（integration.nonces，唯一键 (nonce, partner_id)，R06 §integration）。
 *
 * <p>SQL 与改造前的 JdbcTemplate 版逐字一致：{@code ON CONFLICT DO NOTHING} 的原子防重放
 * 语义原样保留（唯一键冲突即不写入，受影响行数为 0 → 判定为重放）。</p>
 */
public interface NonceMapper {

    /** nonce 登记（唯一键冲突即不写入，受影响行数 0 = 重放）。 */
    @Insert("INSERT INTO integration.nonces (nonce, partner_id) VALUES (#{nonce}, #{partnerId}) "
            + "ON CONFLICT DO NOTHING")
    int insertNonce(@Param("nonce") String nonce, @Param("partnerId") long partnerId);

    /** 惰性清理：删除留存期之前的过期 nonce，返回删除行数。 */
    @Delete("DELETE FROM integration.nonces WHERE used_at < #{cutoff}")
    int deleteNoncesBefore(@Param("cutoff") Instant cutoff);
}
