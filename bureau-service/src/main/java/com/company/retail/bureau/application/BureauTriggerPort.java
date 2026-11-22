package com.company.retail.bureau.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 报送触发登记读取/状态推进端口（表 core.bureau_report_triggers，V14）。
 *
 * <p><b>真相源边界</b>：本表是 servicing（BC-9）的触发侧真相源，bureau-service 作为
 * <b>报送执行方</b>（R02 §6）只做读取（按 loanId+trigger_type 定位、扫描到期触发）
 * 与状态推进（REGISTERED → CONSUMED / BLOCKED），不新增/改写触发登记本身。</p>
 */
public interface BureauTriggerPort {

    /** 按 (loan_id, trigger_type) 定位（uk_brt_loan_type 唯一约束的读取侧）。 */
    Optional<TriggerRow> findByLoanAndType(long loanId, String triggerType);

    /** 到期触发扫描（批处理用）：status=REGISTERED 且 target_biz_date <= today。 */
    List<TriggerRow> findDue(LocalDate today);

    /** 状态推进：REGISTERED → CONSUMED（已报送）/ BLOCKED（同意门禁拦截等）。 */
    boolean updateStatus(long triggerId, String status);

    /** 触发行（bureau 侧只读投影；不含 PII）。 */
    record TriggerRow(long triggerId, long loanId, String dpdStage, String triggerType,
                      long consentId, LocalDate targetBizDate, String status) {
    }
}
