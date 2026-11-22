package com.company.retail.bureau.application;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.contract.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * DPD_CHANGED 消费方（BC-9 servicing → BC-4 bureau 报送执行的最后一公里）。
 *
 * <p>W7 的触发登记（core.bureau_report_triggers）+ 本消费者 = 报送闭环：
 * 事件到达 → 定位同债权的 REGISTERED 触发 → 经 {@link BureauReportExecutionService}
 * 创建 integration.credit_bureau_reports → BureauAdapter（挡板）报送 →
 * bureau_message_logs 留痕 → SENT → CREDIT_BUREAU_REPORT_SENT（聚合键 reportId）。</p>
 *
 * <p>消费铁律（R09 §5.4）：幂等（uk_report_loan_trigger + event_consumptions 双层）／
 * 不吞异常（缺业务主键直接抛，调度器计数 DEAD）／不跨服务调用（只读共库表、只写本域）。</p>
 */
@Component
public class DpdChangedReportConsumer implements EventConsumer {

    static final String CONSUMER_NAME = "bureau-service:dpd-report";

    /** 触发登记的逾期触发类型（W7 BureauReportTriggerAppService.TRIGGER_DELINQUENCY 同值）。 */
    static final String TRIGGER_TYPE_DELINQUENCY = "DELINQUENCY";

    private static final Logger log = LoggerFactory.getLogger(DpdChangedReportConsumer.class);

    private final BureauTriggerPort triggerPort;
    private final BureauReportExecutionService executionService;

    public DpdChangedReportConsumer(BureauTriggerPort triggerPort,
                                    BureauReportExecutionService executionService) {
        this.triggerPort = triggerPort;
        this.executionService = executionService;
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OutboxPublisher.DPD_CHANGED);
    }

    @Override
    public void consume(DomainEvent event) {
        Long loanId = event.longField("loanId");
        if (loanId == null || loanId <= 0) {
            // 不吞异常：缺业务主键按坏事件处理（调度器重试 3 次后 DEAD，R09 §5.4 铁律 2）
            throw new IllegalStateException("DPD_CHANGED payload 缺 loanId（R09 §5.1 聚合键）");
        }
        var trigger = triggerPort.findByLoanAndType(loanId, TRIGGER_TYPE_DELINQUENCY);
        if (trigger.isEmpty() || !"REGISTERED".equals(trigger.get().status())) {
            // 无触发登记或已消费/拦截：事件本身不承担登记职责（W7 登记侧真相源），跳过
            log.info("DPD_CHANGED 无待执行触发登记，跳过 loanId={} triggerStatus={} event_id={}",
                    loanId, trigger.map(BureauTriggerPort.TriggerRow::status).orElse("NO_TRIGGER"),
                    event.eventId());
            return;
        }
        String traceId = event.textField("traceId");
        var result = executionService.executeTrigger(trigger.get(),
                traceId == null || traceId.isBlank() ? "evt-" + event.eventId() : traceId);
        log.info("DPD_CHANGED 报送执行完成 loanId={} reportId={} outcome={} event_id={}",
                loanId, result.reportId(), result.outcome(), event.eventId());
    }
}
