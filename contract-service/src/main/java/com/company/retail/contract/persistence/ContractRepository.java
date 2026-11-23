package com.company.retail.contract.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.contract.domain.Contract;
import com.company.retail.contract.domain.ContractStatus;
import com.company.retail.contract.domain.TransactionScheme;
import com.company.retail.contract.mapper.ContractMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code core.contracts} 持久化（MyBatis 实现；V5 表；权威写者 BC-5 contract-service，P-5）。
 * 枚举 ↔ VARCHAR 转换收口在行组装（R00 §5）。
 */
@Repository
public class ContractRepository {

    private final ContractMapper mapper;

    public ContractRepository(ContractMapper mapper) {
        this.mapper = mapper;
    }

    public long insert(Contract contract, String termsSnapshotJson, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("contractNo", contractNoOf(contract.applicationId()));
        params.put("applicationId", contract.applicationId());
        params.put("customerId", contract.customerId());
        params.put("transactionScheme", contract.scheme().name());
        params.put("status", contract.status().name());
        params.put("signedAt", contract.signedAt() == null ? null : Timestamp.from(contract.signedAt()));
        params.put("effectiveAt", contract.effectiveAt() == null ? null : Timestamp.from(contract.effectiveAt()));
        params.put("coolingOffUntil", contract.coolingOffUntil() == null
                ? null : Date.valueOf(contract.coolingOffUntil()));
        params.put("termsSnapshot", termsSnapshotJson);
        params.put("traceId", traceId);
        // 主键由驱动回填到 params 的 contractId 键（原实现用 GeneratedKeyHolder 取值，语义等价）
        mapper.insert(params);
        Object contractId = params.get("contractId");
        return contractId == null ? 0 : ((Number) contractId).longValue();
    }

    /** 状态推进 + 乐观锁 version（并发口径 R00 §5；行锁由 UPDATE ... WHERE version 保证）。 */
    public int updateAfterSigning(Contract contract, String termsSnapshotJson) {
        return mapper.updateAfterSigning(
                contract.status().name(),
                contract.signedAt() == null ? null : Timestamp.from(contract.signedAt()),
                contract.effectiveAt() == null ? null : Timestamp.from(contract.effectiveAt()),
                contract.coolingOffUntil() == null ? null : Date.valueOf(contract.coolingOffUntil()),
                termsSnapshotJson,
                contract.contractId(),
                contract.version());
    }

    /** 仅状态推进（cancel / terminate / 交付留痕后的 DOCUMENTS_DELIVERED）。 */
    public int updateStatus(Contract contract) {
        return mapper.updateStatus(
                contract.status().name(),
                contract.signedAt() == null ? null : Timestamp.from(contract.signedAt()),
                contract.effectiveAt() == null ? null : Timestamp.from(contract.effectiveAt()),
                contract.coolingOffUntil() == null ? null : Date.valueOf(contract.coolingOffUntil()),
                contract.contractId(),
                contract.version());
    }

    public Optional<Contract> findById(long contractId) {
        List<Map<String, Object>> rows = mapper.findById(contractId);
        return rows.stream().findFirst().map(ContractRepository::toContract);
    }

    public Optional<Contract> findByApplicationId(long applicationId) {
        List<Map<String, Object>> rows = mapper.findByApplicationId(applicationId);
        return rows.stream().findFirst().map(ContractRepository::toContract);
    }

    /** contract_no 定源：一个申请一份合同（uk_contract_app），派生键确定性唯一。 */
    public static String contractNoOf(long applicationId) {
        return "CT-%d".formatted(applicationId);
    }

    /** 行 → 聚合（列名小写下划线，读取口径与原 RowMapper 一致）。 */
    private static Contract toContract(Map<String, Object> row) {
        return Contract.restore(
                RowValues.asLong(row, "contract_id"),
                RowValues.asLong(row, "application_id"),
                RowValues.asLong(row, "customer_id"),
                TransactionScheme.valueOf(RowValues.asString(row, "transaction_scheme")),
                ContractStatus.valueOf(RowValues.asString(row, "status")),
                instant(row, "signed_at"),
                instant(row, "effective_at"),
                RowValues.asLocalDate(row, "cooling_off_until"),
                RowValues.asInt(row, "version"));
    }

    /** TIMESTAMPTZ → Instant（对齐原 {@code rs.getTimestamp(...).toInstant()}）。 */
    private static Instant instant(Map<String, Object> row, String col) {
        Timestamp timestamp = RowValues.asTimestamp(row, col);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
