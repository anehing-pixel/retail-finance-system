package com.company.retail.externalapi.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * partner 档案 MyBatis Mapper（integration.partners，R06 §integration）。
 *
 * <p>SQL 与改造前的 JdbcTemplate 版逐字一致：主键回查按 partner_code，
 * 档案行只取 status / scope / ip_allowlist 三列（scope 为 JSONB，驱动给出文本形态）。</p>
 */
public interface PartnerMapper {

    /** partner 主键回查（按 partner_code；无行返回空列表）。 */
    @Select("SELECT partner_id FROM integration.partners WHERE partner_code = #{partnerCode}")
    List<Long> findPartnerIdsByCode(@Param("partnerCode") String partnerCode);

    /** partner 档案行（status / scope / ip_allowlist）。 */
    @Select("SELECT status, scope, ip_allowlist FROM integration.partners "
            + "WHERE partner_code = #{partnerCode}")
    List<Map<String, Object>> findPartnerRows(@Param("partnerCode") String partnerCode);
}
