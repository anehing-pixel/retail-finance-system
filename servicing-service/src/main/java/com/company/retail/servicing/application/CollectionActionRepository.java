package com.company.retail.servicing.application;

import java.util.List;

/**
 * 催收动作留痕仓储端口（表 core.collection_actions；append-only，无 update/delete）。
 */
public interface CollectionActionRepository {

    void insert(long caseId, String actionType, String outcome, String note,
                String actor, String actorSubject, String traceId);

    List<ActionRow> listByCase(long caseId);

    record ActionRow(long actionId, long caseId, String actionType, String outcome,
                     String note, String actor, String actorSubject, String traceId) {
    }
}
