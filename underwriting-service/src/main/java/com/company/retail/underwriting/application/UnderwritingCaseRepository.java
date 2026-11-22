package com.company.retail.underwriting.application;

import com.company.retail.underwriting.domain.UnderwritingCase;

import java.util.Optional;

/** 审查案件仓储端口（infrastructure 提供 JDBC 实现；单测用内存实现）。 */
public interface UnderwritingCaseRepository {

    /** 插入新案件（PENDING），回填 caseId。 */
    void insert(UnderwritingCase testCase);

    /** 按主键查询。 */
    Optional<UnderwritingCase> findById(long caseId);

    /** 乐观锁更新：version 不匹配返回 false（并发修改）。 */
    boolean update(UnderwritingCase testCase);
}
