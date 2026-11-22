package com.company.retail.underwriting.infrastructure;

import com.company.retail.underwriting.application.UnderwritingCaseRepository;
import com.company.retail.underwriting.domain.DecisionOutcome;
import com.company.retail.underwriting.domain.UnderwritingCase;
import com.company.retail.underwriting.domain.UnderwritingStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * 审查案件 JDBC 仓储（表 core.underwriting_cases，V4 + V11 consent_id）。
 *
 * <p>状态变更只经聚合方法后再落库（R00 §5：禁止直接 UPDATE SET status 绕过状态机——
 * 本类只接受聚合的当前快照，不做任何状态字段的单列改写）。
 * 乐观锁 version 兜底并发（R06 §2）。</p>
 */
public class JdbcUnderwritingCaseRepository implements UnderwritingCaseRepository {

    private static final RowMapper<UnderwritingCase> ROW_MAPPER = (rs, i) -> UnderwritingCase.restore(
            rs.getLong("case_id"),
            rs.getLong("application_id"),
            rs.getLong("consent_id"),
            rs.getString("status"),
            rs.getString("outcome"),
            rs.getString("decided_by"),
            rs.getString("reviewed_by"),
            rs.getObject("decided_at", java.time.OffsetDateTime.class),
            rs.getString("rule_version"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public JdbcUnderwritingCaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UnderwritingCase testCase) {
        Long caseId;
        try {
            caseId = jdbc.queryForObject(
                    "INSERT INTO core.underwriting_cases (application_id, consent_id, status, rule_version) "
                            + "VALUES (?, ?, ?, ?) RETURNING case_id",
                    Long.class,
                    testCase.getApplicationId(), testCase.getConsentId(),
                    testCase.getStatus().name(), testCase.getRuleVersion());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_uwc_app：同一申请重复建案（X-1 幂等去重）→ RF_CONFLICT_001
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_CONFLICT_001,
                    java.util.Map.of("reason", "CASE_DUPLICATE",
                            "applicationId", String.valueOf(testCase.getApplicationId())));
        }
        testCase.assignId(caseId);
    }

    @Override
    public Optional<UnderwritingCase> findById(long caseId) {
        List<UnderwritingCase> rows = jdbc.query(
                "SELECT case_id, application_id, consent_id, status, outcome, decided_by, reviewed_by, "
                        + "decided_at, rule_version, version FROM core.underwriting_cases WHERE case_id = ?",
                ROW_MAPPER, caseId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public boolean update(UnderwritingCase testCase) {
        int updated = jdbc.update(
                "UPDATE core.underwriting_cases SET status = ?, outcome = ?, decided_by = ?, "
                        + "reviewed_by = ?, decided_at = ?, rule_version = ?, version = version + 1, "
                        + "updated_at = now() WHERE case_id = ? AND version = ?",
                ps -> {
                    ps.setString(1, testCase.getStatus().name());
                    if (testCase.getOutcome() == null) {
                        ps.setNull(2, Types.VARCHAR);
                    } else {
                        ps.setString(2, testCase.getOutcome().name());
                    }
                    ps.setString(3, testCase.getDecidedBy());
                    ps.setString(4, testCase.getReviewedBy());
                    if (testCase.getDecidedAt() == null) {
                        ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
                    } else {
                        ps.setObject(5, testCase.getDecidedAt());
                    }
                    ps.setString(6, testCase.getRuleVersion());
                    ps.setLong(7, testCase.getCaseId());
                    ps.setInt(8, testCase.getVersion());
                });
        if (updated > 0) {
            testCase.bumpVersion();
        }
        return updated > 0;
    }
}
