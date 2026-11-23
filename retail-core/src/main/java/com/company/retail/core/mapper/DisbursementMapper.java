package com.company.retail.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 代付 MyBatis Mapper（表 core.disbursements，V6）。
 *
 * <p>uk_disb_loan（INV-1 一合同只一次代付）+ uk_disb_idempotency 双唯一约束兜底。</p>
 */
public interface DisbursementMapper {

    @Insert("INSERT INTO core.disbursements (loan_id, payee_account_ref, amount, status, "
            + "instruction_ref, paid_at, idempotency_key, trace_id) "
            + "VALUES (#{loanId}, #{payeeAccountRef}, #{amount}, #{status}, "
            + "#{instructionRef}, #{paidAt}, #{idempotencyKey}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "disbursementId", keyColumn = "disbursement_id")
    int insert(Map<String, Object> params);

    @Select("SELECT disbursement_id, loan_id, payee_account_ref, amount, status, instruction_ref, "
            + "paid_at, idempotency_key, version FROM core.disbursements WHERE loan_id = #{loanId}")
    List<Map<String, Object>> findByLoan(@Param("loanId") long loanId);

    @Select("SELECT disbursement_id, loan_id, payee_account_ref, amount, status, instruction_ref, "
            + "paid_at, idempotency_key, version FROM core.disbursements "
            + "WHERE disbursement_id = #{disbursementId}")
    List<Map<String, Object>> findById(@Param("disbursementId") long disbursementId);
}
