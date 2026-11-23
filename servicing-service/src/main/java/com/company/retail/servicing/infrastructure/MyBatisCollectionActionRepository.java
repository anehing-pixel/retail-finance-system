package com.company.retail.servicing.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.servicing.application.CollectionActionRepository;
import com.company.retail.servicing.mapper.CollectionActionMapper;

import java.util.List;
import java.util.Map;

/**
 * 催收动作留痕 MyBatis 仓储（core.collection_actions；append-only，无 update/delete）。
 */
public class MyBatisCollectionActionRepository implements CollectionActionRepository {

    private final CollectionActionMapper mapper;

    public MyBatisCollectionActionRepository(CollectionActionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(long caseId, String actionType, String outcome, String note,
                       String actor, String actorSubject, String traceId) {
        mapper.insert(caseId, actionType, outcome, note, actor, actorSubject, traceId);
    }

    @Override
    public List<ActionRow> listByCase(long caseId) {
        return mapper.listByCase(caseId).stream()
                .map(MyBatisCollectionActionRepository::toRow)
                .toList();
    }

    private static ActionRow toRow(Map<String, Object> r) {
        return new ActionRow(
                RowValues.asLong(r, "action_id"),
                RowValues.asLong(r, "case_id"),
                RowValues.asString(r, "action_type"),
                RowValues.asString(r, "outcome"),
                RowValues.asString(r, "note"),
                RowValues.asString(r, "actor"),
                RowValues.asString(r, "actor_subject"),
                RowValues.asString(r, "trace_id"));
    }
}
