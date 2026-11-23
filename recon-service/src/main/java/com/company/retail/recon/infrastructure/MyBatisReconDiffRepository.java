package com.company.retail.recon.infrastructure;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.recon.application.ReconDiffRepository;
import com.company.retail.recon.mapper.ReconDiffMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对拍差异清单 MyBatis 实现（core.recon_diffs，V14；不物理删除，状态表达生命周期）。
 *
 * <p>uk_recon_diff_open 为部分唯一索引（inv_code, entity_type, entity_id）WHERE status = 'OPEN'：
 * 同一不一致项只留一行 OPEN，重跑不产生第二份数据（A28 的表级兜底）。</p>
 */
public class MyBatisReconDiffRepository implements ReconDiffRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReconDiffMapper mapper;

    public MyBatisReconDiffRepository(ReconDiffMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean upsertOpen(String jobName, String bizDate, String invCode, String entityType,
                              String entityId, Map<String, Object> detail, String traceId) {
        try {
            mapper.insert(jobName, LocalDate.parse(bizDate), invCode, entityType, entityId,
                    toJson(detail), traceId);
            return true;
        } catch (DuplicateKeyException e) {
            // uk_recon_diff_open：已有 OPEN → 原位更新明细（重跑不产生第二份数据，A28）
            mapper.updateDetail(jobName, LocalDate.parse(bizDate), invCode, entityType, entityId,
                    toJson(detail), traceId);
            return false;
        }
    }

    @Override
    public List<OpenKey> listOpenKeys() {
        return mapper.listOpenKeys().stream()
                .map(r -> new OpenKey(RowValues.asString(r, "inv_code"),
                        RowValues.asString(r, "entity_type"),
                        String.valueOf(r.get("entity_id"))))
                .toList();
    }

    @Override
    public boolean resolve(OpenKey key, String traceId) {
        return mapper.resolve(key.invCode(), key.entityType(), key.entityId(), traceId) == 1;
    }

    @Override
    public List<DiffRow> list(String invCodeOrNull, String statusOrNull) {
        return mapper.list(invCodeOrNull, statusOrNull).stream()
                .map(MyBatisReconDiffRepository::toRow)
                .toList();
    }

    @Override
    public Optional<DiffRow> findById(long diffId) {
        List<Map<String, Object>> rows = mapper.findById(diffId);
        return rows.stream().findFirst().map(MyBatisReconDiffRepository::toRow);
    }

    private static DiffRow toRow(Map<String, Object> r) {
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
        return new DiffRow(RowValues.asLong(r, "diff_id"),
                RowValues.asString(r, "job_name"),
                String.valueOf(r.get("biz_date")),
                RowValues.asString(r, "inv_code"),
                RowValues.asString(r, "entity_type"),
                String.valueOf(r.get("entity_id")),
                detailMap,
                RowValues.asString(r, "status"));
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
