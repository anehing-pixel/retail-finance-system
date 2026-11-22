package com.company.retail.appservice.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * core.dealer_partners 只读投影仓储（ADR-RF-010）：
 * floorplan 是权威写者，本表只允许经同步入口写入快照，绝不反向写 floorplan。
 */
@Repository
public class JdbcDealerPartnerRepository {

    private final JdbcTemplate jdbc;

    public JdbcDealerPartnerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 投影 upsert（同步成功路径，sync_status=SYNCED）。 */
    public long upsertSynced(String dealerMasterRef, String nameSnapshot, String status, String traceId) {
        jdbc.update("""
                INSERT INTO core.dealer_partners (dealer_master_ref, sync_status, name_snapshot, status, trace_id)
                VALUES (?, 'SYNCED', ?, ?, ?)
                ON CONFLICT (dealer_master_ref)
                DO UPDATE SET sync_status = 'SYNCED', name_snapshot = EXCLUDED.name_snapshot,
                              status = EXCLUDED.status, trace_id = EXCLUDED.trace_id,
                              updated_at = now(), version = core.dealer_partners.version + 1
                """, dealerMasterRef, nameSnapshot, status == null ? "ACTIVE" : status, traceId);
        Long id = jdbc.queryForObject(
                "SELECT dealer_partner_id FROM core.dealer_partners WHERE dealer_master_ref = ?",
                Long.class, dealerMasterRef);
        return id == null ? -1 : id;
    }

    /** 同步失败降级人工：先落 UNSYNCED 行，等人工核对后重触发（R05 补偿路径）。 */
    public long markUnsynced(String dealerMasterRef, String nameSnapshot, String traceId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO core.dealer_partners (dealer_master_ref, sync_status, name_snapshot, status, trace_id)
                    VALUES (?, 'UNSYNCED', ?, 'ACTIVE', ?)
                    ON CONFLICT (dealer_master_ref)
                    DO UPDATE SET sync_status = 'UNSYNCED', name_snapshot = EXCLUDED.name_snapshot,
                                  trace_id = EXCLUDED.trace_id, updated_at = now(),
                                  version = core.dealer_partners.version + 1
                    """, new String[]{"dealer_partner_id"});
            ps.setString(1, dealerMasterRef);
            ps.setString(2, nameSnapshot);
            ps.setString(3, traceId);
            return ps;
        }, kh);
        Long id = jdbc.queryForObject(
                "SELECT dealer_partner_id FROM core.dealer_partners WHERE dealer_master_ref = ?",
                Long.class, dealerMasterRef);
        return id == null ? -1 : id;
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList("""
                SELECT dealer_partner_id, dealer_master_ref, sync_status, name_snapshot, status
                FROM core.dealer_partners ORDER BY dealer_partner_id
                """);
    }
}
