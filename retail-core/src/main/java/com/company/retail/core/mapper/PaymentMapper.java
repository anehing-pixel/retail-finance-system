package com.company.retail.core.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 入金与核销 MyBatis Mapper（表 core.payments / core.payment_allocations，V7）。
 *
 * <p>不变式：uk_payment_account_txn（X-6 重复入金明细去重）、uk_alloc_payment（一笔入金只核销一次）。</p>
 */
public interface PaymentMapper {

    @Insert("INSERT INTO core.payments (payment_ref, account_ref, transaction_id, amount, "
            + "received_at, channel, status, trace_id) "
            + "VALUES (#{paymentRef}, #{accountRef}, #{transactionId}, #{amount}, "
            + "#{receivedAt}, #{channel}, #{status}, #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "paymentId", keyColumn = "payment_id")
    int insert(Map<String, Object> params);

    @Select("SELECT payment_id, payment_ref, account_ref, transaction_id, amount, received_at, "
            + "channel, status, version FROM core.payments WHERE payment_id = #{paymentId}")
    List<Map<String, Object>> findById(@Param("paymentId") long paymentId);

    @Insert("INSERT INTO core.payment_allocations (payment_id, loan_id, schedule_item_id, "
            + "fee_amount, interest_amount, principal_amount, allocated_at, trace_id) "
            + "VALUES (#{paymentId}, #{loanId}, #{scheduleItemId}, #{feeAmount}, #{interestAmount}, "
            + "#{principalAmount}, now(), #{traceId})")
    @Options(useGeneratedKeys = true, keyProperty = "allocationId", keyColumn = "allocation_id")
    int insertAllocation(Map<String, Object> params);

    @Select("SELECT COALESCE(SUM(fee_amount), 0) AS fee_total, "
            + "COALESCE(SUM(interest_amount), 0) AS interest_total, "
            + "COALESCE(SUM(principal_amount), 0) AS principal_total "
            + "FROM core.payment_allocations WHERE loan_id = #{loanId}")
    List<Map<String, Object>> sumAllocationsByLoan(@Param("loanId") long loanId);

    @Update("UPDATE core.payments SET status = #{status}, version = version + 1, updated_at = now() "
            + "WHERE payment_id = #{paymentId}")
    int updateStatus(@Param("paymentId") long paymentId, @Param("status") String status);
}
