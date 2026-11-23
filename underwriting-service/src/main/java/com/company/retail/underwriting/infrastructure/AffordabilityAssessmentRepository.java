package com.company.retail.underwriting.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.underwriting.domain.AssessmentOutput;
import com.company.retail.underwriting.domain.AssessmentResult;
import com.company.retail.underwriting.domain.AffordabilityInput;
import com.company.retail.underwriting.mapper.AffordabilityAssessmentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 支付能力评估快照 MyBatis 仓储（表 core.affordability_assessments，append-only 不重算历史）。
 *
 * <p>input_snapshot 只存评估输入（收入为<b>加密引用码</b>，无 PII 明文，R06 §4.2）。</p>
 *
 * <p>实现类与端口同名（去 Jdbc 前缀），故此处以全限定名引用端口，避免同编译单元同名冲突。</p>
 */
public class AffordabilityAssessmentRepository
        implements com.company.retail.underwriting.application.AffordabilityAssessmentRepository {

    private final AffordabilityAssessmentMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AffordabilityAssessmentRepository(AffordabilityAssessmentMapper mapper) {
        this.mapper = mapper;
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
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("caseId", caseId);
        params.put("annualIncomeEncRef", input.annualIncomeEncRef());
        params.put("existingDebtTotal", input.existingDebtTotal());
        params.put("thresholdAmount", input.thresholdAmount());
        params.put("result", output.result().name());
        params.put("policyVersion", output.policyVersion());
        params.put("inputSnapshot", inputJson);
        try {
            mapper.insert(params);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_aff_case_version：append-only 快照不覆盖历史（RF_CONFLICT_001）
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_CONFLICT_001,
                    java.util.Map.of("reason", "ASSESSMENT_EXISTS", "caseId", String.valueOf(caseId)));
        }
    }

    @Override
    public Optional<AssessmentOutput> findLatest(long caseId) {
        List<Map<String, Object>> rows = mapper.findLatest(caseId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toOutput(rows.get(0)));
    }

    /** 行 → 评估结果（reasonCode 固定 SNAPSHOT，与原 RowMapper 一致）。 */
    private static AssessmentOutput toOutput(Map<String, Object> row) {
        return new AssessmentOutput(
                AssessmentResult.valueOf(RowValues.asString(row, "result")),
                "SNAPSHOT",
                RowValues.asString(row, "policy_version"));
    }
}
