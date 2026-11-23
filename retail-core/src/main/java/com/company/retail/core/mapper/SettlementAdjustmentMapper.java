package com.company.retail.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 结清调整 MyBatis Mapper（表 core.settlement_adjustments，V13；append-only，一债权一次结清）。
 */
public interface SettlementAdjustmentMapper {

    @Insert("INSERT INTO core.settlement_adjustments (loan_id, settlement_type, principal_waiver, "
            + "interest_waiver, fee_waiver, settlement_fee, trace_id) "
            + "VALUES (#{loanId}, #{settlementType}, #{principalWaiver}, #{interestWaiver}, "
            + "#{feeWaiver}, #{settlementFee}, #{traceId})")
    int insert(@Param("loanId") long loanId, @Param("settlementType") String settlementType,
               @Param("principalWaiver") long principalWaiver, @Param("interestWaiver") long interestWaiver,
               @Param("feeWaiver") long feeWaiver, @Param("settlementFee") long settlementFee,
               @Param("traceId") String traceId);

    @Select("SELECT COALESCE(SUM(principal_waiver), 0) AS pw, COALESCE(SUM(interest_waiver), 0) AS iw, "
            + "COALESCE(SUM(fee_waiver), 0) AS fw, COALESCE(SUM(settlement_fee), 0) AS sf "
            + "FROM core.settlement_adjustments WHERE loan_id = #{loanId}")
    List<Map<String, Object>> sumByLoan(@Param("loanId") long loanId);
}
