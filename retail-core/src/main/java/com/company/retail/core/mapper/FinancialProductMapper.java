package com.company.retail.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 产品档位目录 MyBatis Mapper（表 core.financial_products，V18）。
 *
 * <p>选版口径：生效日 &lt;= 业务日 &lt; 失效日 且启用；同码多版本按生效日倒序取一版。</p>
 */
public interface FinancialProductMapper {

    /** 当前生效档位（一个 code 至多一行命中）。 */
    @Select("SELECT product_id, product_code, product_version, product_name, scheme, term_count, "
            + "rate_bp, bonus_month, waiver_cap_bp, effective_from, effective_to, enabled, version "
            + "FROM core.financial_products WHERE product_code = #{productCode} "
            + "AND enabled = TRUE AND effective_from <= #{bizDate} "
            + "AND (effective_to IS NULL OR effective_to > #{bizDate}) "
            + "ORDER BY effective_from DESC, product_version DESC LIMIT 1")
    List<Map<String, Object>> findEffective(@Param("productCode") String productCode,
                                            @Param("bizDate") LocalDate bizDate);

    /** 同码全部版本（变更审计视图）。 */
    @Select("SELECT product_id, product_code, product_version, product_name, scheme, term_count, "
            + "rate_bp, bonus_month, waiver_cap_bp, effective_from, effective_to, enabled, version "
            + "FROM core.financial_products WHERE product_code = #{productCode} "
            + "ORDER BY product_version DESC")
    List<Map<String, Object>> findVersions(@Param("productCode") String productCode);

    /** 新增一个档位版本。 */
    @Insert("INSERT INTO core.financial_products (product_code, product_version, product_name, scheme, "
            + "term_count, rate_bp, bonus_month, waiver_cap_bp, effective_from, effective_to, "
            + "enabled, trace_id) "
            + "VALUES (#{productCode}, #{productVersion}, #{productName}, #{scheme}, #{termCount}, "
            + "#{rateBp}, #{bonusMonth}, #{waiverCapBp}, #{effectiveFrom}, #{effectiveTo}, "
            + "#{enabled}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "productId", keyColumn = "product_id")
    int insert(Map<String, Object> params);

    /** 旧版本封口停用（改版时用；乐观锁 version 兜底并发改配置）。 */
    @Update("UPDATE core.financial_products SET effective_to = #{effectiveTo}, enabled = FALSE, "
            + "version = version + 1, updated_at = now() "
            + "WHERE product_id = #{productId} AND version = #{version}")
    int retire(@Param("productId") long productId,
               @Param("effectiveTo") LocalDate effectiveTo,
               @Param("version") int version);
}
