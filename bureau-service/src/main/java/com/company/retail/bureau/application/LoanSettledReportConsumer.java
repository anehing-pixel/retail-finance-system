package com.company.retail.bureau.application;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeCipher;
import com.company.retail.common.messaging.EnvelopePublisher;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * LOAN_SETTLED 消费方（BC-6 retail-core → BC-4 结清报送；R05 §「关闭并报送结清」）。
 *
 * <p>结清信息报送与逾期报送走<b>同一执行通道</b>
 * （{@link BureauReportExecutionService#executeSettlement}，trigger=SETTLED）：
 * consentId 经决定时同意锚点链解析（INV-5 consent_id NOT NULL），
 * 报文落 integration.bureau_message_logs，成功发 CREDIT_BUREAU_REPORT_SENT。</p>
 *
 * <p><b>ADR-RF-014 削峰分支</b>：MQ 开启时封密文信封入队，由
 * {@link com.company.retail.bureau.messaging.BureauReportEnvelopeConsumer} 解密后执行同一通道；
 * 关闭时行为与原来逐字一致。</p>
 */
@Component
public class LoanSettledReportConsumer implements EventConsumer {

    static final String CONSUMER_NAME = "bureau-service:settled-report";

    private static final Logger log = LoggerFactory.getLogger(LoanSettledReportConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BureauReportExecutionService executionService;
    private final ObjectProvider<EnvelopePublisher> publisherProvider;
    private final ObjectProvider<EnvelopeCipher> cipherProvider;

    /** 既有构造器（保留给单测）：无 MQ 通道，始终走同步路径。 */
    public LoanSettledReportConsumer(BureauReportExecutionService executionService) {
        this(executionService, null, null);
    }

    @Autowired
    public LoanSettledReportConsumer(BureauReportExecutionService executionService,
                                    ObjectProvider<EnvelopePublisher> publisherProvider,
                                    ObjectProvider<EnvelopeCipher> cipherProvider) {
        this.executionService = executionService;
        this.publisherProvider = publisherProvider;
        this.cipherProvider = cipherProvider;
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
        String effectiveTraceId = traceId == null || traceId.isBlank() ? "evt-" + event.eventId() : traceId;

        EnvelopePublisher publisher = publisherProvider == null ? null : publisherProvider.getIfAvailable();
        if (publisher != null && publisher.enabled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "SETTLEMENT");
            m.put("loanId", loanId);
            m.put("traceId", effectiveTraceId);
            String payloadJson;
            try {
                payloadJson = MAPPER.writeValueAsString(m);
            } catch (Exception e) {
                throw new IllegalStateException("结清报送载荷序列化失败", e);
            }
            EnvelopeCipher cipher = cipherProvider == null ? null : cipherProvider.getIfAvailable();
            if (cipher == null) {
                throw new IllegalStateException("MQ 通道已启用但信封加解密器缺失，拒绝降级为同步报送");
            }
            CipherEnvelope envelope = cipher.seal(EnvelopeTopics.BUREAU, String.valueOf(loanId),
                    payloadJson, effectiveTraceId);
            publisher.publish(EnvelopeTopics.BUREAU, envelope);
            log.info("LOAN_SETTLED 结清报送已封密文入队 loanId={} envelopeId={} event_id={}",
                    loanId, envelope.envelopeId(), event.eventId());
            return;
        }

        var result = executionService.executeSettlement(loanId, effectiveTraceId);
        log.info("LOAN_SETTLED 结清报送完成 loanId={} reportId={} outcome={} event_id={}",
                loanId, result.reportId(), result.outcome(), event.eventId());
    }
}
