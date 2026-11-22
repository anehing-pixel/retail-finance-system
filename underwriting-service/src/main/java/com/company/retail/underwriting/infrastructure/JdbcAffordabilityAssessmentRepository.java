package com.company.retail.underwriting.infrastructure;

import com.company.retail.underwriting.application.AffordabilityAssessmentRepository;
import com.company.retail.underwriting.domain.AffordabilityInput;
import com.company.retail.underwriting.domain.AssessmentOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 支付能力评估快照 JDBC 仓储（表 core.affordability_assessments，append-only 不重算历史）。
 *
 * <p>input_snapshot 只存评估输入（收入为<b>加密引用码</b>，无 PII 明文，R06 §4.2）。</p>
 */
public class JdbcAffordabilityAssessmentRepository implements AffordabilityAssessmentRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcAffordabilityAssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(long caseId, AffordabilityInput input, AssessmentOutput output, String traceId) {
        String inputJson;
        try {
            inputJson = objectMapper.writeValueAsString(Map.of(
                    "scheme", String.valueOf(input.scheme()),
                    "annualIncomeEncRef", String.valueOf(input.annualIncomeEncRef()),
                    "existingDebtTotal", input.existingDebtTotal(),
                    "thresholdAmount", input.thresholdAmount(),
                    "requestedAmount", input.requestedAmount()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("评估输入快照序列化失败", e);
        }
        try {
            jdbc.update("INSERT INTO core.affordability_assessments "
                            + "(case_id, annual_income_enc_ref, existing_debt_total, threshold_amount, "
                            + "result, policy_version, input_snapshot) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                    caseId, input.annualIncomeEncRef(), input.existingDebtTotal(),
                    input.thresholdAmount(), output.result().name(), output.policyVersion(), inputJson);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_aff_case_version：append-only 快照不覆盖历史（RF_CONFLICT_001）
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_CONFLICT_001,
                    java.util.Map.of("reason", "ASSESSMENT_EXISTS", "caseId", String.valueOf(caseId)));
        }
    }

    @Override
    public Optional<AssessmentOutput> findLatest(long caseId) {
        List<AssessmentOutput> rows = jdbc.query(
                "SELECT result, policy_version FROM core.affordability_assessments "
                        + "WHERE case_id = ? ORDER BY assessment_id DESC LIMIT 1",
                (rs, i) -> new AssessmentOutput(
                        com.company.retail.underwriting.domain.AssessmentResult.valueOf(rs.getString("result")),
                        "SNAPSHOT",
                        rs.getString("policy_version")),
                caseId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
