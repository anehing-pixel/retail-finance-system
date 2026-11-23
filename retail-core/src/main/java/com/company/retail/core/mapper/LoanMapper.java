package com.company.retail.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 债权 MyBatis Mapper（表 core.loans，V6 + V13 结清列；权威写者 BC-6 retail-core，P-5）。
 *
 * <p>账务并发口径（R06 §7）：代付/核销/结清路径一律 {@code SELECT ... FOR UPDATE} 行锁 +
 * {@code version} 乐观锁双兜底（禁止无锁读-改-写，R00 §5）。</p>
 */
public interface LoanMapper {

    @Insert("INSERT INTO core.loans (loan_no, contract_id, status, principal, fee_amount, balance, trace_id) "
            + "VALUES (#{loanNo}, #{contractId}, #{status}, #{principal}, #{feeAmount}, #{balance}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "loanId", keyColumn = "loan_id")
    int insert(Map<String, Object> params);

    @Update("UPDATE core.loans SET status = #{status}, disbursed_at = #{disbursedAt}, "
            + "version = version + 1, updated_at = now() "
            + "WHERE loan_id = #{loanId} AND version = #{version}")
    int updateAfterDisbursement(@Param("status") String status, @Param("disbursedAt") Timestamp disbursedAt,
                                @Param("loanId") long loanId, @Param("version") int version);

    @Update("UPDATE core.loans SET interest_accrued = #{interestAccrued}, balance = #{balance}, "
            + "version = version + 1, updated_at = now() "
            + "WHERE loan_id = #{loanId} AND version = #{version}")
    int updateAfterPlanGeneration(@Param("interestAccrued") long interestAccrued,
                                   @Param("balance") long balance,
                                   @Param("loanId") long loanId, @Param("version") int version);

    @Update("UPDATE core.loans SET status = #{status}, balance = #{balance}, "
            + "version = version + 1, updated_at = now() "
            + "WHERE loan_id = #{loanId} AND version = #{version}")
    int updateAfterAllocation(@Param("status") String status, @Param("balance") long balance,
                               @Param("loanId") long loanId, @Param("version") int version);

    @Update("UPDATE core.loans SET status = #{status}, balance = #{balance}, settled_at = #{settledAt}, "
            + "settlement_type = #{settlementType}, waiver_amount = #{waiverAmount}, "
            + "settlement_fee = #{settlementFee}, version = version + 1, updated_at = now() "
            + "WHERE loan_id = #{loanId} AND version = #{version}")
    int updateAfterSettlement(@Param("status") String status, @Param("balance") long balance,
                               @Param("settledAt") Timestamp settledAt,
                               @Param("settlementType") String settlementType,
                               @Param("waiverAmount") long waiverAmount,
                               @Param("settlementFee") long settlementFee,
                               @Param("loanId") long loanId, @Param("version") int version);

    @Select("SELECT loan_id, contract_id, status, principal, fee_amount, interest_accrued, balance, "
            + "disbursed_at, settled_at, settlement_type, waiver_amount, settlement_fee, version "
            + "FROM core.loans WHERE loan_id = #{loanId}")
    List<Map<String, Object>> findById(@Param("loanId") long loanId);

    @Select("SELECT loan_id, contract_id, status, principal, fee_amount, interest_accrued, balance, "
            + "disbursed_at, settled_at, settlement_type, waiver_amount, settlement_fee, version "
            + "FROM core.loans WHERE contract_id = #{contractId}")
    List<Map<String, Object>> findByContractId(@Param("contractId") long contractId);

    /** 账务行锁（R06 §7：loans 更新必须 SELECT ... FOR UPDATE）。 */
    @Select("SELECT loan_id, contract_id, status, principal, fee_amount, interest_accrued, balance, "
            + "disbursed_at, settled_at, settlement_type, waiver_amount, settlement_fee, version "
            + "FROM core.loans WHERE loan_id = #{loanId} FOR UPDATE")
    List<Map<String, Object>> findByIdForUpdate(@Param("loanId") long loanId);
}
