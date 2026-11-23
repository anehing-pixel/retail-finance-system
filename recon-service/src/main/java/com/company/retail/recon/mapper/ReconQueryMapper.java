package com.company.retail.recon.mapper;

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 对拍只读查询 MyBatis Mapper（V14 对拍视图 + outbox 扫描）。
 *
 * <p><b>边界说明</b>：对拍天然要跨真相源读数——本 Mapper 只做 SELECT
 * （对拍视图 core.v_recon_inv1/2/3/5 + core.outbox_events），不写任何他域表；
 * 跨域只读经视图显式声明，是对 R02 §6「只读走 Feign」的对拍例外。</p>
 */
public interface ReconQueryMapper {

    /** INV-1：一份合同的代付只发生一次，金额 = 债权本金约定。 */
    @Select("SELECT loan_id, loan_status, principal, disb_count, paid_amount "
            + "FROM core.v_recon_inv1_disbursement")
    List<Map<String, Object>> inv1();

    /** INV-2：余额精确相等（不容差）。 */
    @Select("SELECT loan_id, loan_status, actual_balance, expected_balance "
            + "FROM core.v_recon_inv2_balance")
    List<Map<String, Object>> inv2();

    /** INV-3 前半：债权未终态即建 ReleaseOrder（解押门禁）。 */
    @Select("SELECT release_order_id, loan_id, loan_status FROM core.v_recon_inv3_release_gating")
    List<Map<String, Object>> inv3ReleaseGating();

    /** INV-3 后半：无外部回执却已进 RELEASE_REGISTERED。 */
    @Select("SELECT title_id, loan_id, release_order_id FROM core.v_recon_inv3_receipt_authority")
    List<Map<String, Object>> inv3ReceiptAuthority();

    /** INV-4：outbox payload 键白名单扫描（R09 §5.2 默认拒绝）。 */
    @Select("SELECT event_id, event_type, payload FROM core.outbox_events ORDER BY event_id")
    List<Map<String, Object>> outboxEvents();

    /** INV-5：查询/报送留痕必须锚定存在且未撤销的同意。 */
    @Select("SELECT entity_type, entity_id, consent_id, record_status "
            + "FROM core.v_recon_inv5_consent")
    List<Map<String, Object>> inv5Consent();
}
