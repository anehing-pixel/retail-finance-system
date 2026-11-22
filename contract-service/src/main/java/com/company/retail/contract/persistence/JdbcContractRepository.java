package com.company.retail.contract.persistence;

import com.company.retail.contract.domain.Contract;
import com.company.retail.contract.domain.ContractStatus;
import com.company.retail.contract.domain.TransactionScheme;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code core.contracts} 持久化（V5 表；权威写者 BC-5 contract-service，P-5）。
 * 枚举 ↔ VARCHAR 转换收口在 RowMapper（R00 §5）。
 */
@Repository
public class JdbcContractRepository {

    private static final RowMapper<Contract> MAPPER = (rs, i) -> Contract.restore(
            rs.getLong("contract_id"),
            rs.getLong("application_id"),
            rs.getLong("customer_id"),
            TransactionScheme.valueOf(rs.getString("transaction_scheme")),
            ContractStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("signed_at") == null ? null : rs.getTimestamp("signed_at").toInstant(),
            rs.getTimestamp("effective_at") == null ? null : rs.getTimestamp("effective_at").toInstant(),
            rs.getDate("cooling_off_until") == null ? null : rs.getDate("cooling_off_until").toLocalDate(),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public JdbcContractRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Contract contract, String termsSnapshotJson, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO core.contracts (contract_no, application_id, customer_id, transaction_scheme, "
                            + "status, signed_at, effective_at, cooling_off_until, terms_snapshot, trace_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                    new String[]{"contract_id"});
            ps.setString(1, contractNoOf(contract.applicationId()));
            ps.setLong(2, contract.applicationId());
            ps.setLong(3, contract.customerId());
            ps.setString(4, contract.scheme().name());
            ps.setString(5, contract.status().name());
            ps.setTimestamp(6, contract.signedAt() == null ? null : Timestamp.from(contract.signedAt()));
            ps.setTimestamp(7, contract.effectiveAt() == null ? null : Timestamp.from(contract.effectiveAt()));
            ps.setDate(8, contract.coolingOffUntil() == null ? null
                    : Date.valueOf(contract.coolingOffUntil()));
            ps.setString(9, termsSnapshotJson);
            ps.setString(10, traceId);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    /** 状态推进 + 乐观锁 version（并发口径 R00 §5；行锁由 UPDATE ... WHERE version 保证）。 */
    public int updateAfterSigning(Contract contract, String termsSnapshotJson) {
        return jdbc.update(
                "UPDATE core.contracts SET status = ?, signed_at = ?, effective_at = ?, cooling_off_until = ?, "
                        + "terms_snapshot = ?::jsonb, version = version + 1, updated_at = now() "
                        + "WHERE contract_id = ? AND version = ?",
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
        return jdbc.update(
                "UPDATE core.contracts SET status = ?, signed_at = ?, effective_at = ?, cooling_off_until = ?, "
                        + "version = version + 1, updated_at = now() "
                        + "WHERE contract_id = ? AND version = ?",
                contract.status().name(),
                contract.signedAt() == null ? null : Timestamp.from(contract.signedAt()),
                contract.effectiveAt() == null ? null : Timestamp.from(contract.effectiveAt()),
                contract.coolingOffUntil() == null ? null : Date.valueOf(contract.coolingOffUntil()),
                contract.contractId(),
                contract.version());
    }

    public Optional<Contract> findById(long contractId) {
        List<Contract> rows = jdbc.query(
                "SELECT * FROM core.contracts WHERE contract_id = ?", MAPPER, contractId);
        return rows.stream().findFirst();
    }

    public Optional<Contract> findByApplicationId(long applicationId) {
        List<Contract> rows = jdbc.query(
                "SELECT * FROM core.contracts WHERE application_id = ?", MAPPER, applicationId);
        return rows.stream().findFirst();
    }

    /** contract_no 定源：一个申请一份合同（uk_contract_app），派生键确定性唯一。 */
    public static String contractNoOf(long applicationId) {
        return "CT-%d".formatted(applicationId);
    }
}
