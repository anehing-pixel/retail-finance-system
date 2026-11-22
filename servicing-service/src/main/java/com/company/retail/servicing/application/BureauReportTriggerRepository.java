package com.company.retail.servicing.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 征信报送触发登记仓储端口（表 core.bureau_report_triggers，V14）。
 *
 * <p>servicing 只做<b>触发登记与状态跟踪</b>；integration.credit_bureau_reports 的
 * 写者是 bureau-service（R02 §6 真相源边界），本表是触发侧真相源。</p>
 */
public interface BureauReportTriggerRepository {

    /** 登记（uk_brt_loan_type 一债权一触发类型）；重复返回 false。 */
    boolean insert(long loanId, String dpdStage, String triggerType, long consentId,
                   LocalDate targetBizDate, String traceId);

    Optional<TriggerRow> findById(long triggerId);

    Optional<TriggerRow> findByLoanAndType(long loanId, String triggerType);

    List<TriggerRow> list(String statusOrNull);

    /** 状态跟踪更新（REGISTERED → CONSUMED / BLOCKED）。 */
    boolean updateStatus(long triggerId, String status, String traceId);

    record TriggerRow(long triggerId, long loanId, String dpdStage, String triggerType,
                      long consentId, LocalDate targetBizDate, String status) {
    }
}
