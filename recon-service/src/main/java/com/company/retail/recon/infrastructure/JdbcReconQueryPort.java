package com.company.retail.recon.infrastructure;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.recon.application.ReconFinding;
import com.company.retail.recon.application.ReconQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对拍查询 JDBC 实现（只读；V14 对拍视图 + outbox 扫描）。
 *
 * <p><b>边界说明</b>：对拍天然要跨真相源读数——本实现只做 SELECT（对拍视图
 * core.v_recon_inv1/2/3/5 + core.outbox_events），不写任何他域表；跨域只读经视图显式
 * 声明，是对 R02 §6「只读走 Feign」的对拍例外（结论随最终报告回灌文档）。</p>
 */
public class JdbcReconQueryPort implements ReconQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcReconQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReconFinding> collectFindings() {
        List<ReconFinding> findings = new ArrayList<>();
        findings.addAll(inv1());
        findings.addAll(inv2());
        findings.addAll(inv3());
        findings.addAll(inv4());
        findings.addAll(inv5());
        return findings;
    }

    /** INV-1：代付唯一且金额=本金约定（视图已判差异，这里读出行即差异）。 */
    private List<ReconFinding> inv1() {
        List<ReconFinding> list = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT loan_id, loan_status, principal, disb_count, paid_amount "
                        + "FROM core.v_recon_inv1_disbursement")) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("loanStatus", r.get("loan_status"));
            detail.put("principal", ((Number) r.get("principal")).longValue());
            detail.put("disbCount", ((Number) r.get("disb_count")).intValue());
            detail.put("paidAmount", r.get("paid_amount") == null
                    ? null : ((Number) r.get("paid_amount")).longValue());
            list.add(new ReconFinding("INV-1", "LOAN",
                    String.valueOf(((Number) r.get("loan_id")).longValue()), detail));
        }
        return list;
    }

    /** INV-2：余额精确相等（不容差）。 */
    private List<ReconFinding> inv2() {
        List<ReconFinding> list = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT loan_id, loan_status, actual_balance, expected_balance "
                        + "FROM core.v_recon_inv2_balance")) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("loanStatus", r.get("loan_status"));
            detail.put("actualBalance", ((Number) r.get("actual_balance")).longValue());
            detail.put("expectedBalance", ((Number) r.get("expected_balance")).longValue());
            list.add(new ReconFinding("INV-2", "LOAN",
                    String.valueOf(((Number) r.get("loan_id")).longValue()), detail));
        }
        return list;
    }

    /** INV-3：解押门禁（债权未终态建 ReleaseOrder / 无外部回执进 RELEASE_REGISTERED）。 */
    private List<ReconFinding> inv3() {
        List<ReconFinding> list = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT release_order_id, loan_id, loan_status FROM core.v_recon_inv3_release_gating")) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("loanId", ((Number) r.get("loan_id")).longValue());
            detail.put("loanStatus", r.get("loan_status"));
            detail.put("reason", "RELEASE_ORDER_BEFORE_LOAN_TERMINAL");
            list.add(new ReconFinding("INV-3", "RELEASE_ORDER",
                    String.valueOf(((Number) r.get("release_order_id")).longValue()), detail));
        }
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT title_id, loan_id, release_order_id FROM core.v_recon_inv3_receipt_authority")) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("loanId", ((Number) r.get("loan_id")).longValue());
            detail.put("releaseOrderId", ((Number) r.get("release_order_id")).longValue());
            detail.put("reason", "RELEASE_REGISTERED_WITHOUT_EXTERNAL_RECEIPT");
            list.add(new ReconFinding("INV-3", "TITLE_RETENTION",
                    String.valueOf(((Number) r.get("title_id")).longValue()), detail));
        }
        return list;
    }

    /**
     * INV-4：outbox payload 键白名单扫描（R09 §5.2 默认拒绝；复用发布端同一守卫，
     * 保证「能发布的一定合法，非法的发布不出来」与对拍判定同源）。
     */
    private List<ReconFinding> inv4() {
        List<ReconFinding> list = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT event_id, event_type, payload FROM core.outbox_events ORDER BY event_id")) {
            long eventId = ((Number) r.get("event_id")).longValue();
            String eventType = (String) r.get("event_type");
            String payload = (String) r.get("payload");
            try {
                EventPayloadGuard.validate(eventType, payload);
            } catch (Exception violation) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("eventType", eventType);
                detail.put("violation", String.valueOf(violation.getMessage()));
                list.add(new ReconFinding("INV-4", "OUTBOX_EVENT", String.valueOf(eventId), detail));
            }
        }
        return list;
    }

    /** INV-5：查询/报送留痕必须锚定存在且未撤销的同意。 */
    private List<ReconFinding> inv5() {
        List<ReconFinding> list = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT entity_type, entity_id, consent_id, record_status "
                        + "FROM core.v_recon_inv5_consent")) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("consentId", r.get("consent_id") == null
                    ? null : ((Number) r.get("consent_id")).longValue());
            detail.put("recordStatus", r.get("record_status"));
            detail.put("reason", "CONSENT_MISSING_OR_NOT_GRANTED");
            list.add(new ReconFinding("INV-5",
                    (String) r.get("entity_type"),
                    String.valueOf(((Number) r.get("entity_id")).longValue()), detail));
        }
        return list;
    }
}
