package com.company.retail.recon.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对拍差异清单仓储端口（表 core.recon_diffs，V14；不物理删除，状态表达生命周期）。
 */
public interface ReconDiffRepository {

    /** 落一条 OPEN 差异（同 (inv_code, entity_type, entity_id) 已有 OPEN → 原位更新明细，不重复）。 */
    boolean upsertOpen(String jobName, String bizDate, String invCode, String entityType,
                       String entityId, Map<String, Object> detail, String traceId);

    /** 全部 OPEN 差异键（用于检出「已修复」项 → RESOLVED）。 */
    List<OpenKey> listOpenKeys();

    /** 标记已修复（对拍重跑时未再检出）。 */
    boolean resolve(OpenKey key, String traceId);

    List<DiffRow> list(String invCodeOrNull, String statusOrNull);

    Optional<DiffRow> findById(long diffId);

    record OpenKey(String invCode, String entityType, String entityId) {
    }

    record DiffRow(long diffId, String jobName, String bizDate, String invCode,
                   String entityType, String entityId, Map<String, Object> detail,
                   String status) {
    }
}
