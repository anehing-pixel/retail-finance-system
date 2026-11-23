package com.company.retail.recon.infrastructure;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.persistence.RowValues;
import com.company.retail.recon.application.ReconFinding;
import com.company.retail.recon.application.ReconQueryPort;
import com.company.retail.recon.mapper.ReconQueryMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对拍查询 MyBatis 实现（只读；V14 对拍视图 + outbox 扫描）。
 *
 * <p><b>边界说明</b>：对拍天然要跨真相源读数——本实现只做 SELECT（对拍视图
 * core.v_recon_inv1/2/3/5 + core.outbox_events），不写任何他域表；跨域只读经视图显式
 * 声明，是对 R02 §6「只读走 Feign」的对拍例外（结论随最终报告回灌文档）。</p>
 */
public class MyBatisReconQueryPort implements ReconQueryPort {

    private final ReconQueryMapper mapper;

    public MyBatisReconQueryPort(ReconQueryMapper mapper) {
        this.mapper = mapper;
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
        for (Map<String, Object> r : mapper.inv1()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("loanStatus", RowValues.asString(r, "loan_status"));
            detail.put("principal", RowValues.asLong(r, "principal"));
            detail.put("disbCount", RowValues.asInt(r, "disb_count"));
            detail.put("paidAmount", RowValues.asLongOrNull(r, "paid_amount"));
            list.add(new ReconFinding("INV-1", "LOAN",
                    String.valueOf(RowValues.asLong(r, "loan_id")), detail));
        }
        return list;
    }

    /** INV-2：余额精确相等（不容差）。 */
    private List<ReconFinding> inv2() {
        List<ReconFinding> list = new ArrayList<>();
        for (Map<String, Object> r : mapper.inv2()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("loanStatus", RowValues.asString(r, "loan_status"));
            detail.put("actualBalance", RowValues.asLong(r, "actual_balance"));
            detail.put("expectedBalance", RowValues.asLong(r, "expected_balance"));
            list.add(new ReconFinding("INV-2", "LOAN",
                    String.valueOf(RowValues.asLong(r, "loan_id")), detail));
        }
        return list;
    }

    /** INV-3：解押门禁（债权未终态建 ReleaseOrder / 无外部回执进 RELEASE_REGISTERED）。 */
    private List<ReconFinding> inv3() {
        List<ReconFinding> list = new ArrayList<>();
        for (Map<String, Object> r : mapper.inv3ReleaseGating()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("loanId", RowValues.asLong(r, "loan_id"));
            detail.put("loanStatus", RowValues.asString(r, "loan_status"));
            detail.put("reason", "RELEASE_ORDER_BEFORE_LOAN_TERMINAL");
            list.add(new ReconFinding("INV-3", "RELEASE_ORDER",
                    String.valueOf(RowValues.asLong(r, "release_order_id")), detail));
        }
        for (Map<String, Object> r : mapper.inv3ReceiptAuthority()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("loanId", RowValues.asLong(r, "loan_id"));
            detail.put("releaseOrderId", RowValues.asLong(r, "release_order_id"));
            detail.put("reason", "RELEASE_REGISTERED_WITHOUT_EXTERNAL_RECEIPT");
            list.add(new ReconFinding("INV-3", "TITLE_RETENTION",
                    String.valueOf(RowValues.asLong(r, "title_id")), detail));
        }
        return list;
    }

    /**
     * INV-4：outbox payload 键白名单扫描（R09 §5.2 默认拒绝；复用发布端同一守卫，
     * 保证「能发布的一定合法，非法的发布不出来」与对拍判定同源）。
     */
    private List<ReconFinding> inv4() {
        List<ReconFinding> list = new ArrayList<>();
        for (Map<String, Object> r : mapper.outboxEvents()) {
            long eventId = RowValues.asLong(r, "event_id");
            String eventType = RowValues.asString(r, "event_type");
            String payload = String.valueOf(r.get("payload"));
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
        for (Map<String, Object> r : mapper.inv5Consent()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("consentId", RowValues.asLongOrNull(r, "consent_id"));
            detail.put("recordStatus", RowValues.asString(r, "record_status"));
            detail.put("reason", "CONSENT_MISSING_OR_NOT_GRANTED");
            list.add(new ReconFinding("INV-5",
                    RowValues.asString(r, "entity_type"),
                    String.valueOf(RowValues.asLong(r, "entity_id")), detail));
        }
        return list;
    }
}
