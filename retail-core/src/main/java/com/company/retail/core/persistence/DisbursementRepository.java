package com.company.retail.core.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.core.mapper.DisbursementMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code core.disbursements} 持久化（V6 表）。
 * uk_disb_loan（INV-1 一合同只一次代付）+ uk_disb_idempotency 双唯一约束兜底。
 */
@Repository
public class DisbursementRepository {

    public record DisbursementRow(long disbursementId, long loanId, String payeeAccountRef, long amount,
                                  String status, String instructionRef, Timestamp paidAt,
                                  String idempotencyKey, int version) {
    }

    private final DisbursementMapper mapper;

    public DisbursementRepository(DisbursementMapper mapper) {
        this.mapper = mapper;
    }

    public long insert(long loanId, String payeeAccountRef, long amount, String status,
                       String instructionRef, Timestamp paidAt, String idempotencyKey, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("loanId", loanId);
        params.put("payeeAccountRef", payeeAccountRef);
        params.put("amount", amount);
        params.put("status", status);
        params.put("instructionRef", instructionRef);
        params.put("paidAt", paidAt);
        params.put("idempotencyKey", idempotencyKey);
        params.put("traceId", traceId);
        mapper.insert(params);
        Object id = params.get("disbursementId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    public Optional<DisbursementRow> findByLoan(long loanId) {
        List<Map<String, Object>> rows = mapper.findByLoan(loanId);
        return rows.stream().findFirst().map(DisbursementRepository::toRow);
    }

    public Optional<DisbursementRow> findById(long disbursementId) {
        List<Map<String, Object>> rows = mapper.findById(disbursementId);
        return rows.stream().findFirst().map(DisbursementRepository::toRow);
    }

    private static DisbursementRow toRow(Map<String, Object> row) {
        return new DisbursementRow(
                RowValues.asLong(row, "disbursement_id"),
                RowValues.asLong(row, "loan_id"),
                RowValues.asString(row, "payee_account_ref"),
                RowValues.asLong(row, "amount"),
                RowValues.asString(row, "status"),
                RowValues.asString(row, "instruction_ref"),
                RowValues.asTimestamp(row, "paid_at"),
                RowValues.asString(row, "idempotency_key"),
                RowValues.asInt(row, "version"));
    }
}
