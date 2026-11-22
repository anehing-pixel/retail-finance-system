package com.company.retail.servicing.infrastructure;

import com.company.retail.servicing.application.CollectionActionRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 催收动作留痕 JDBC 仓储（core.collection_actions；append-only，无 update/delete）。
 */
public class JdbcCollectionActionRepository implements CollectionActionRepository {

    private final JdbcTemplate jdbc;

    public JdbcCollectionActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(long caseId, String actionType, String outcome, String note,
                       String actor, String actorSubject, String traceId) {
        jdbc.update("INSERT INTO core.collection_actions "
                        + "(case_id, action_type, outcome, note, actor, actor_subject, trace_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                caseId, actionType, outcome, note, actor, actorSubject, traceId);
    }

    @Override
    public List<ActionRow> listByCase(long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT action_id, case_id, action_type, outcome, note, actor, actor_subject, trace_id "
                        + "FROM core.collection_actions WHERE case_id = ? ORDER BY action_id", caseId);
        return rows.stream().map(r -> new ActionRow(
                ((Number) r.get("action_id")).longValue(),
                ((Number) r.get("case_id")).longValue(),
                (String) r.get("action_type"),
                (String) r.get("outcome"),
                (String) r.get("note"),
                (String) r.get("actor"),
                (String) r.get("actor_subject"),
                (String) r.get("trace_id"))).toList();
    }
}
