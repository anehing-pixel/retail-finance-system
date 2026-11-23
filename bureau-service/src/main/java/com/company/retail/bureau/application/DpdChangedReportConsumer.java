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
 * DPD_CHANGED 消费方（BC-9 servicing → BC-4 bureau 报送执行的最后一公里）。
 *
 * <p>W7 的触发登记（core.bureau_report_triggers）+ 本消费者 = 报送闭环：
 * 事件到达 → 定位同债权的 REGISTERED 触发 → 经 {@link BureauReportExecutionService}
 * 创建 integration.credit_bureau_reports → BureauAdapter（挡板）报送 →
 * bureau_message_logs 留痕 → SENT → CREDIT_BUREAU_REPORT_SENT（聚合键 reportId）。</p>
 *
 * <p>消费铁律（R09 §5.4）：幂等（uk_report_loan_trigger + event_consumptions 双层）／
 * 不吞异常（缺业务主键直接抛，调度器计数 DEAD）／不跨服务调用（只读共库表、只写本域）。</p>
 *
 * <p><b>ADR-RF-014 削峰分支</b>：MQ 开启时，本消费者不再就地执行报送（那一步含外部机构调用，
 * 是洪峰来源），而是把「触发登记快照」封成密文信封投进 MQ，由
 * {@link com.company.retail.bureau.messaging.BureauReportEnvelopeConsumer} 解密后执行——
 * 事件侧立即返回，外部调用速率由消费侧限速。关闭时行为与原来逐字一致。</p>
 */
@Component
public class DpdChangedReportConsumer implements EventConsumer {

    static final String CONSUMER_NAME = "bureau-service:dpd-report";

    /** 触发登记的逾期触发类型（W7 BureauReportTriggerAppService.TRIGGER_DELINQUENCY 同值）。 */
    static final String TRIGGER_TYPE_DELINQUENCY = "DELINQUENCY";

    private static final Logger log = LoggerFactory.getLogger(DpdChangedReportConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BureauTriggerPort triggerPort;
    private final BureauReportExecutionService executionService;
    private final ObjectProvider<EnvelopePublisher> publisherProvider;
    private final ObjectProvider<EnvelopeCipher> cipherProvider;

    /** 既有构造器（保留给单测）：无 MQ 通道，始终走同步路径。 */
    public DpdChangedReportConsumer(BureauTriggerPort triggerPort,
                                    BureauReportExecutionService executionService) {
        this(triggerPort, executionService, null, null);
    }

    @Autowired
    public DpdChangedReportConsumer(BureauTriggerPort triggerPort,
                                    BureauReportExecutionService executionService,
                                    ObjectProvider<EnvelopePublisher> publisherProvider,
                                    ObjectProvider<EnvelopeCipher> cipherProvider) {
        this.triggerPort = triggerPort;
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
        String effectiveTraceId = traceId == null || traceId.isBlank() ? "evt-" + event.eventId() : traceId;

        EnvelopePublisher publisher = publisherProvider == null ? null : publisherProvider.getIfAvailable();
        if (publisher != null && publisher.enabled()) {
            // 削峰分支：密文入队，报送执行交给消费者（外部机构调用不再压在事件消费线程上）
            String payloadJson = triggerPayload(trigger.get(), effectiveTraceId);
            EnvelopeCipher cipher = cipherProvider == null ? null : cipherProvider.getIfAvailable();
            if (cipher == null) {
                throw new IllegalStateException("MQ 通道已启用但信封加解密器缺失，拒绝降级为同步报送");
            }
            CipherEnvelope envelope = cipher.seal(EnvelopeTopics.BUREAU, String.valueOf(loanId),
                    payloadJson, effectiveTraceId);
            publisher.publish(EnvelopeTopics.BUREAU, envelope);
            log.info("DPD_CHANGED 报送已封密文入队 loanId={} envelopeId={} event_id={}",
                    loanId, envelope.envelopeId(), event.eventId());
            return;
        }

        var result = executionService.executeTrigger(trigger.get(), effectiveTraceId);
        log.info("DPD_CHANGED 报送执行完成 loanId={} reportId={} outcome={} event_id={}",
                loanId, result.reportId(), result.outcome(), event.eventId());
    }

    /** 触发登记快照（只含业务键，不含个人信息）。 */
    static String triggerPayload(BureauTriggerPort.TriggerRow row, String traceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "TRIGGER");
        m.put("triggerId", row.triggerId());
        m.put("loanId", row.loanId());
        m.put("dpdStage", row.dpdStage());
        m.put("triggerType", row.triggerType());
        m.put("consentId", row.consentId());
        m.put("targetBizDate", row.targetBizDate() == null ? "" : row.targetBizDate().toString());
        m.put("status", row.status());
        m.put("traceId", traceId);
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("触发快照序列化失败", e);
        }
    }
}
