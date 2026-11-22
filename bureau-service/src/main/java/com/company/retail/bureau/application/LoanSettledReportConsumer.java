package com.company.retail.bureau.application;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.contract.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * LOAN_SETTLED 消费方（BC-6 retail-core → BC-4 结清报送；R05 §「关闭并报送结清」）。
 *
 * <p>结清信息报送与逾期报送走<b>同一执行通道</b>
 * （{@link BureauReportExecutionService#executeSettlement}，trigger=SETTLED）：
 * consentId 经决定时同意锚点链解析（INV-5 consent_id NOT NULL），
 * 报文落 integration.bureau_message_logs，成功发 CREDIT_BUREAU_REPORT_SENT。</p>
 */
@Component
public class LoanSettledReportConsumer implements EventConsumer {

    static final String CONSUMER_NAME = "bureau-service:settled-report";

    private static final Logger log = LoggerFactory.getLogger(LoanSettledReportConsumer.class);

    private final BureauReportExecutionService executionService;

    public LoanSettledReportConsumer(BureauReportExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OutboxPublisher.LOAN_SETTLED);
    }

    @Override
    public void consume(DomainEvent event) {
        Long loanId = event.longField("loanId");
        if (loanId == null || loanId <= 0) {
            // 不吞异常：缺业务主键按坏事件处理（调度器重试 3 次后 DEAD，R09 §5.4 铁律 2）
            throw new IllegalStateException("LOAN_SETTLED payload 缺 loanId（R09 §5.1 聚合键）");
        }
        String traceId = event.textField("traceId");
        var result = executionService.executeSettlement(loanId,
                traceId == null || traceId.isBlank() ? "evt-" + event.eventId() : traceId);
        log.info("LOAN_SETTLED 结清报送完成 loanId={} reportId={} outcome={} event_id={}",
                loanId, result.reportId(), result.outcome(), event.eventId());
    }
}
