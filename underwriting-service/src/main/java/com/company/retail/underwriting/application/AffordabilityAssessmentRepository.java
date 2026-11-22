package com.company.retail.underwriting.application;

import com.company.retail.underwriting.domain.AssessmentOutput;
import com.company.retail.underwriting.domain.AffordabilityInput;

import java.util.Optional;

/** 支付能力评估快照仓储端口（append-only，不重算历史，R06 uk_aff_case_version）。 */
public interface AffordabilityAssessmentRepository {

    /** 追加快照（case_id + policy_version 唯一）。 */
    void insert(long caseId, AffordabilityInput input, AssessmentOutput output, String traceId);

    /** 查询某案件某策略版本的既有快照（幂等判定用）。 */
    Optional<AssessmentOutput> findLatest(long caseId);
}
