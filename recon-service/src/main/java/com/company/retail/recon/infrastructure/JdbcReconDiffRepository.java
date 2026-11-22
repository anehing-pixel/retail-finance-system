package com.company.retail.recon.infrastructure;

import com.company.retail.recon.application.ReconDiffRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对拍差异清单 JDBC 实现（core.recon_diffs，V14；不物理删除，状态表达生命周期）。
 */
public class JdbcReconDiffRepository implements ReconDiffRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcReconDiffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean upsertOpen(String jobName, String bizDate, String invCode, String entityType,
                              String entityId, Map<String, Object> detail, String traceId) {
        try {
            jdbc.update("INSERT INTO core.recon_diffs "
                            + "(job_name, biz_date, inv_code, entity_type, entity_id, detail, status, trace_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, 'OPEN', ?)",
                    jobName, LocalDate.parse(bizDate), invCode, entityType, entityId,
                    toJson(detail), traceId);
            return true;
        } catch (DuplicateKeyException e) {
            // uk_recon_diff_open：已有 OPEN → 原位更新明细（重跑不产生第二份数据，A28）
            jdbc.update("UPDATE core.recon_diffs SET detail = ?::jsonb, job_name = ?, biz_date = ?, "
                            + "trace_id = ?, updated_at = now(), version = version + 1 "
                            + "WHERE inv_code = ? AND entity_type = ? AND entity_id = ? AND status = 'OPEN'",
                    toJson(detail), jobName, LocalDate.parse(bizDate), traceId,
                    invCode, entityType, entityId);
            return false;
        }
    }

    @Override
    public List<OpenKey> listOpenKeys() {
        return jdbc.queryForList("SELECT inv_code, entity_type, entity_id FROM core.recon_diffs "
                        + "WHERE status = 'OPEN'").stream()
                .map(r -> new OpenKey((String) r.get("inv_code"), (String) r.get("entity_type"),
                        String.valueOf(r.get("entity_id"))))
                .toList();
    }

    @Override
    public boolean resolve(OpenKey key, String traceId) {
        return jdbc.update("UPDATE core.recon_diffs SET status = 'RESOLVED', trace_id = ?, "
                        + "updated_at = now(), version = version + 1 "
                        + "WHERE inv_code = ? AND entity_type = ? AND entity_id = ? AND status = 'OPEN'",
                traceId, key.invCode(), key.entityType(), key.entityId()) == 1;
    }

    @Override
    public List<DiffRow> list(String invCodeOrNull, String statusOrNull) {
        StringBuilder sql = new StringBuilder(
                "SELECT diff_id, job_name, biz_date, inv_code, entity_type, entity_id, detail, status "
                        + "FROM core.recon_diffs WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (invCodeOrNull != null && !invCodeOrNull.isBlank()) {
            sql.append(" AND inv_code = ?");
            args.add(invCodeOrNull);
        }
        if (statusOrNull != null && !statusOrNull.isBlank()) {
            sql.append(" AND status = ?");
            args.add(statusOrNull);
        }
        sql.append(" ORDER BY diff_id DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(this::toRow).toList();
    }

    @Override
    public Optional<DiffRow> findById(long diffId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT diff_id, job_name, biz_date, inv_code, entity_type, entity_id, detail, status "
                        + "FROM core.recon_diffs WHERE diff_id = ?", diffId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    private DiffRow toRow(Map<String, Object> r) {
        Object detail = r.get("detail");
        Map<String, Object> detailMap = new LinkedHashMap<>();
        if (detail != null) {
            try {
                detailMap = MAPPER.readValue(detail instanceof String s ? s : detail.toString(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            } catch (Exception ignored) {
                detailMap.put("raw", String.valueOf(detail));
            }
        }
        return new DiffRow(((Number) r.get("diff_id")).longValue(),
                (String) r.get("job_name"),
                String.valueOf(r.get("biz_date")),
                (String) r.get("inv_code"),
                (String) r.get("entity_type"),
                String.valueOf(r.get("entity_id")),
                detailMap,
                (String) r.get("status"));
    }

    private static String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(detail);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("对拍差异明细序列化失败", e);
        }
    }
}
