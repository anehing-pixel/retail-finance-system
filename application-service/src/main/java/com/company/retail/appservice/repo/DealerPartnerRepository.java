package com.company.retail.appservice.repo;

import com.company.retail.appservice.mapper.DealerPartnerMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * core.dealer_partners 只读投影仓储（ADR-RF-010）：
 * floorplan 是权威写者，本表只允许经同步入口写入快照，绝不反向写 floorplan。
 *
 * <p>MyBatis 实现：upsert 保留 PostgreSQL 的 {@code ON CONFLICT ... DO UPDATE} 语义。</p>
 */
@Repository
public class DealerPartnerRepository {

    private final DealerPartnerMapper mapper;

    public DealerPartnerRepository(DealerPartnerMapper mapper) {
        this.mapper = mapper;
    }

    /** 投影 upsert（同步成功路径，sync_status=SYNCED）。 */
    public long upsertSynced(String dealerMasterRef, String nameSnapshot, String status, String traceId) {
        mapper.upsertSynced(dealerMasterRef, nameSnapshot, status == null ? "ACTIVE" : status, traceId);
        Long id = mapper.findDealerPartnerIdByRef(dealerMasterRef);
        return id == null ? -1L : id;
    }

    /** 同步失败降级人工：先落 UNSYNCED 行，等人工核对后重触发（R05 补偿路径）。 */
    public long markUnsynced(String dealerMasterRef, String nameSnapshot, String traceId) {
        // 原 JDBC 版此处用 KeyHolder 取 dealer_partner_id 但从未使用（随后按引用码回查），
        // MyBatis 版不取生成键，行为等价。
        mapper.upsertUnsynced(dealerMasterRef, nameSnapshot, traceId);
        Long id = mapper.findDealerPartnerIdByRef(dealerMasterRef);
        return id == null ? -1L : id;
    }

    public List<Map<String, Object>> findAll() {
        return mapper.findAll();
    }
}
