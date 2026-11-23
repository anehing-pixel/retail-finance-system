package com.company.retail.core.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.core.domain.Loan;
import com.company.retail.core.domain.LoanStatus;
import com.company.retail.core.mapper.LoanMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code core.loans} 持久化（V6 表 + V13 结清列；权威写者 BC-6 retail-core，P-5）。
 * 账务并发口径（R06 §7）：代付/核销/结清路径一律 {@code SELECT ... FOR UPDATE} 行锁 +
 * {@code version} 乐观锁双兜底（禁止无锁读-改-写，R00 §5）。
 */
@Repository
public class LoanRepository {

    private final LoanMapper mapper;

    public LoanRepository(LoanMapper mapper) {
        this.mapper = mapper;
    }

    public long insert(Loan loan, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("loanNo", loanNoOf(loan.contractId()));
        params.put("contractId", loan.contractId());
        params.put("status", loan.status().name());
        params.put("principal", loan.principal());
        params.put("feeAmount", loan.feeAmount());
        params.put("balance", loan.balance());
        params.put("traceId", traceId);
        mapper.insert(params);
        Object id = params.get("loanId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    public int updateAfterDisbursement(Loan loan) {
        return mapper.updateAfterDisbursement(
                loan.status().name(),
                loan.disbursedAt() == null ? null : Timestamp.from(loan.disbursedAt()),
                loan.loanId(),
                loan.version());
    }

    /** 还款计划生成：应计利息与余额同步入账（INV-2 利息侧，W6）。 */
    public int updateAfterPlanGeneration(Loan loan) {
        return mapper.updateAfterPlanGeneration(
                loan.interestAccrued(), loan.balance(), loan.loanId(), loan.version());
    }

    /** 入金核销：状态（补救回 ACTIVE 口径）+ 余额乐观锁更新（W6；核销与余额同一本地事务）。 */
    public int updateAfterAllocation(Loan loan) {
        return mapper.updateAfterAllocation(
                loan.status().name(), loan.balance(), loan.loanId(), loan.version());
    }

    /** 结清：终态 + 结清三要素 + settled_at（W6；G10 后唯一入口）。 */
    public int updateAfterSettlement(Loan loan) {
        return mapper.updateAfterSettlement(
                loan.status().name(), loan.balance(),
                loan.settledAt() == null ? null : Timestamp.from(loan.settledAt()),
                loan.settlementType(), loan.waiverAmount(), loan.settlementFee(),
                loan.loanId(), loan.version());
    }

    public Optional<Loan> findById(long loanId) {
        List<Map<String, Object>> rows = mapper.findById(loanId);
        return rows.stream().findFirst().map(LoanRepository::toLoan);
    }

    public Optional<Loan> findByContractId(long contractId) {
        List<Map<String, Object>> rows = mapper.findByContractId(contractId);
        return rows.stream().findFirst().map(LoanRepository::toLoan);
    }

    /** 账务行锁（R06 §7：loans 更新必须 SELECT ... FOR UPDATE）。 */
    public Optional<Loan> findByIdForUpdate(long loanId) {
        List<Map<String, Object>> rows = mapper.findByIdForUpdate(loanId);
        return rows.stream().findFirst().map(LoanRepository::toLoan);
    }

    private static Loan toLoan(Map<String, Object> row) {
        return Loan.restore(
                RowValues.asLong(row, "loan_id"),
                RowValues.asLong(row, "contract_id"),
                LoanStatus.valueOf(RowValues.asString(row, "status")),
                RowValues.asLong(row, "principal"),
                RowValues.asLong(row, "fee_amount"),
                RowValues.asLong(row, "interest_accrued"),
                RowValues.asLong(row, "balance"),
                RowValues.asInstant(row, "disbursed_at"),
                RowValues.asInstant(row, "settled_at"),
                RowValues.asString(row, "settlement_type"),
                RowValues.asLong(row, "waiver_amount"),
                RowValues.asLong(row, "settlement_fee"),
                RowValues.asInt(row, "version"));
    }

    /** loan_no 定源：一合同一债权（uk_loan_contract），派生键确定性唯一。 */
    public static String loanNoOf(long contractId) {
        return "LN-%d".formatted(contractId);
    }
}
