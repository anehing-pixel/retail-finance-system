package com.company.retail.appservice.app;

import com.company.retail.appservice.repo.DealerPartnerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 销售店主数据唯一只读投影用例（ADR-RF-010）：
 * floorplan 是权威写者，本项目只收主数据投影快照；同步失败降级 UNSYNCED 等人工。
 */
@Service
public class DealerProjectionService {

    private final DealerPartnerRepository dealers;

    public DealerProjectionService(DealerPartnerRepository dealers) {
        this.dealers = dealers;
    }

    /**
     * 挡板同步入口（模拟 floorplan 主数据推送；真实链路为 floorplan → DMZ → 本入口）。
     * 成功 → SYNCED；引用码缺失或上游同步失败（simulateFailure，挡板注入）→ 降级 UNSYNCED 人工。
     */
    @Transactional
    public Map<String, Object> sync(String dealerMasterRef, String nameSnapshot, String status,
                                    boolean simulateFailure, String traceId) {
        if (dealerMasterRef == null || dealerMasterRef.isBlank()) {
            // 主数据引用码缺失 = 同步失败 → 降级人工（不留脏投影）
            return Map.of("synced", false, "reasonCode", "MISSING_MASTER_REF");
        }
        if (simulateFailure) {
            // 同步失败降级人工：落 UNSYNCED 行，人工核对后重触发（R05 补偿路径）
            long id = dealers.markUnsynced(dealerMasterRef, nameSnapshot, traceId);
            return Map.of("synced", false, "dealerPartnerId", id,
                    "syncStatus", "UNSYNCED", "reasonCode", "SYNC_FAILED_DEGRADED");
        }
        long id = dealers.upsertSynced(dealerMasterRef, nameSnapshot, status, traceId);
        return Map.of("synced", true, "dealerPartnerId", id, "syncStatus", "SYNCED");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return dealers.findAll();
    }
}
