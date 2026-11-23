package com.company.retail.appservice.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 销售店主数据投影 MyBatis Mapper（core.dealer_partners，ADR-RF-010）。
 *
 * <p>SQL 与改造前的 JdbcTemplate 版逐字一致（仅占位符由 ? 改为 #{name}）：
 * 投影 upsert 保留 PostgreSQL 的 {@code ON CONFLICT ... DO UPDATE} 语义。</p>
 */
public interface DealerPartnerMapper {

    /** 投影 upsert（同步成功路径，sync_status=SYNCED）。 */
    @Insert("INSERT INTO core.dealer_partners (dealer_master_ref, sync_status, name_snapshot, status, "
            + "trace_id) "
            + "VALUES (#{dealerMasterRef}, 'SYNCED', #{nameSnapshot}, #{status}, #{traceId}) "
            + "ON CONFLICT (dealer_master_ref) "
            + "DO UPDATE SET sync_status = 'SYNCED', name_snapshot = EXCLUDED.name_snapshot, "
            + "status = EXCLUDED.status, trace_id = EXCLUDED.trace_id, "
            + "updated_at = now(), version = core.dealer_partners.version + 1")
    int upsertSynced(@Param("dealerMasterRef") String dealerMasterRef,
                     @Param("nameSnapshot") String nameSnapshot,
                     @Param("status") String status,
                     @Param("traceId") String traceId);

    /** 同步失败降级人工：落 UNSYNCED 行（ON CONFLICT 命中即改标记，等人工核对后重触发）。 */
    @Insert("INSERT INTO core.dealer_partners (dealer_master_ref, sync_status, name_snapshot, status, "
            + "trace_id) "
            + "VALUES (#{dealerMasterRef}, 'UNSYNCED', #{nameSnapshot}, 'ACTIVE', #{traceId}) "
            + "ON CONFLICT (dealer_master_ref) "
            + "DO UPDATE SET sync_status = 'UNSYNCED', name_snapshot = EXCLUDED.name_snapshot, "
            + "trace_id = EXCLUDED.trace_id, updated_at = now(), "
            + "version = core.dealer_partners.version + 1")
    int upsertUnsynced(@Param("dealerMasterRef") String dealerMasterRef,
                       @Param("nameSnapshot") String nameSnapshot,
                       @Param("traceId") String traceId);

    /** 按主数据引用码回查投影主键（无行返回 null）。 */
    @Select("SELECT dealer_partner_id FROM core.dealer_partners WHERE dealer_master_ref = #{dealerMasterRef}")
    Long findDealerPartnerIdByRef(@Param("dealerMasterRef") String dealerMasterRef);

    /** 投影列表（按主键升序）。 */
    @Select("SELECT dealer_partner_id, dealer_master_ref, sync_status, name_snapshot, status "
            + "FROM core.dealer_partners ORDER BY dealer_partner_id")
    List<Map<String, Object>> findAll();
}
