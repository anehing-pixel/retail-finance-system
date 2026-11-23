package com.company.retail.servicing.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.servicing.application.CollectionActionRepository;
import com.company.retail.servicing.application.CollectionCaseRepository;
import com.company.retail.servicing.mapper.CollectionActionMapper;
import com.company.retail.servicing.mapper.CollectionCaseMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 催收案件 / 动作留痕 MyBatis 仓储（core.collection_cases + core.collection_actions）。
 *
 * <p>「一债权一在途催收案」由 uk_coll_loan_open 部分唯一索引兜底（PG 表内 UNIQUE 不支持
 * WHERE，V9 已建表外索引，floorplan V19 教训）；冲突时 ON CONFLICT DO NOTHING 返回 0 行。</p>
 */
public class MyBatisCollectionCaseRepository implements CollectionCaseRepository {

    private final CollectionCaseMapper mapper;
    private final CollectionActionRepository actionRepository;

    public MyBatisCollectionCaseRepository(CollectionCaseMapper mapper,
                                           CollectionActionMapper actionMapper) {
        this.mapper = mapper;
        this.actionRepository = new MyBatisCollectionActionRepository(actionMapper);
    }

    /** 动作留痕仓储（同库同事务）。 */
    public CollectionActionRepository actionRepository() {
        return actionRepository;
    }

    /** uk_coll_loan_open 冲突（该债权已有在途案件）返回 false，不抛异常。 */
    @Override
    public boolean insert(long loanId, String dpdStage, String traceId) {
        return mapper.insert(loanId, dpdStage, traceId) == 1;
    }

    @Override
    public Optional<CaseRow> findById(long caseId) {
        List<Map<String, Object>> rows = mapper.findById(caseId);
        return rows.stream().findFirst().map(MyBatisCollectionCaseRepository::toRow);
    }

    @Override
    public Optional<CaseRow> findOpenByLoan(long loanId) {
        List<Map<String, Object>> rows = mapper.findOpenByLoan(loanId);
        return rows.stream().findFirst().map(MyBatisCollectionCaseRepository::toRow);
    }

    @Override
    public boolean updateStatus(long caseId, String status, String assignedTo, boolean closed,
                                long expectedVersion, String traceId) {
        return mapper.updateStatus(caseId, status, assignedTo, closed, expectedVersion, traceId) == 1;
    }

    @Override
    public List<CaseRow> list(String statusOrNull) {
        return mapper.list(statusOrNull).stream()
                .map(MyBatisCollectionCaseRepository::toRow)
                .toList();
    }

    private static CaseRow toRow(Map<String, Object> r) {
        return new CaseRow(
                RowValues.asLong(r, "case_id"),
                RowValues.asLong(r, "loan_id"),
                RowValues.asString(r, "dpd_stage"),
                RowValues.asString(r, "status"),
                RowValues.asString(r, "assigned_to"),
                RowValues.asLong(r, "version"));
    }
}
