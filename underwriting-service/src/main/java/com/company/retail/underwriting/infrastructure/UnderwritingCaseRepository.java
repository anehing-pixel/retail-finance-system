package com.company.retail.underwriting.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.underwriting.domain.UnderwritingCase;
import com.company.retail.underwriting.mapper.UnderwritingCaseMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审查案件 MyBatis 仓储（表 core.underwriting_cases，V4 + V11 consent_id）。
 *
 * <p>状态变更只经聚合方法后再落库（R00 §5：禁止直接 UPDATE SET status 绕过状态机——
 * 本类只接受聚合的当前快照，不做任何状态字段的单列改写）。
 * 乐观锁 version 兜底并发（R06 §2）。</p>
 *
 * <p>实现类与端口同名（去 Jdbc 前缀），故此处以全限定名引用端口，避免同编译单元同名冲突。</p>
 */
public class UnderwritingCaseRepository
        implements com.company.retail.underwriting.application.UnderwritingCaseRepository {

    private final UnderwritingCaseMapper mapper;

    public UnderwritingCaseRepository(UnderwritingCaseMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(UnderwritingCase testCase) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("applicationId", testCase.getApplicationId());
        params.put("consentId", testCase.getConsentId());
        params.put("status", testCase.getStatus().name());
        params.put("ruleVersion", testCase.getRuleVersion());
        try {
            // 主键由驱动回填到 params 的 caseId 键（等价原 RETURNING case_id）
            mapper.insert(params);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // uk_uwc_app：同一申请重复建案（X-1 幂等去重）→ RF_CONFLICT_001
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_CONFLICT_001,
                    java.util.Map.of("reason", "CASE_DUPLICATE",
                            "applicationId", String.valueOf(testCase.getApplicationId())));
        }
        Object caseId = params.get("caseId");
        if (caseId == null) {
            // 驱动未回填主键（等价原 RETURNING 返回空集）
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        }
        testCase.assignId(((Number) caseId).longValue());
    }

    @Override
    public Optional<UnderwritingCase> findById(long caseId) {
        List<Map<String, Object>> rows = mapper.findById(caseId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toCase(rows.get(0)));
    }

    @Override
    public boolean update(UnderwritingCase testCase) {
        int updated = mapper.update(
                testCase.getStatus().name(),
                testCase.getOutcome() == null ? null : testCase.getOutcome().name(),
                testCase.getDecidedBy(),
                testCase.getReviewedBy(),
                testCase.getDecidedAt(),
                testCase.getRuleVersion(),
                testCase.getCaseId(),
                testCase.getVersion());
        if (updated > 0) {
            testCase.bumpVersion();
        }
        return updated > 0;
    }

    /** 行 → 聚合（列名小写下划线，读取口径与原 RowMapper 一致）。 */
    private static UnderwritingCase toCase(Map<String, Object> row) {
        return UnderwritingCase.restore(
                RowValues.asLong(row, "case_id"),
                RowValues.asLong(row, "application_id"),
                RowValues.asLong(row, "consent_id"),
                RowValues.asString(row, "status"),
                RowValues.asString(row, "outcome"),
                RowValues.asString(row, "decided_by"),
                RowValues.asString(row, "reviewed_by"),
                decidedAt(row),
                RowValues.asString(row, "rule_version"),
                RowValues.asInt(row, "version"));
    }

    /**
     * decided_at（TIMESTAMPTZ）→ OffsetDateTime：先收敛为 Instant 再统一以 UTC 偏移表达，
     * 时刻（instant）不变——与原 {@code rs.getObject("decided_at", OffsetDateTime.class)} 落库时刻一致。
     */
    private static OffsetDateTime decidedAt(Map<String, Object> row) {
        Instant instant = RowValues.asInstant(row, "decided_at");
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
