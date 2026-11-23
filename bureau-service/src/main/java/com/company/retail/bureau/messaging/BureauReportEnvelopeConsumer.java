package com.company.retail.bureau.messaging;

import com.company.retail.bureau.application.BureauReportExecutionService;
import com.company.retail.bureau.application.BureauTriggerPort;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 征信链路密文信封消费端（ADR-RF-014 §3）。
 *
 * <p>解密后按 {@code kind} 走<b>既有执行服务</b>：TRIGGER → 逾期报送触发执行，
 * SETTLEMENT → 结清报送。业务幂等（uk_report_loan_trigger）、INV-5 同意门禁、
 * DEAD 兜底等语义全部沿用原通道，本类只负责「解密 + 转交」。</p>
 */
@Component
public class BureauReportEnvelopeConsumer implements EnvelopeConsumer {

    private static final Logger log = LoggerFactory.getLogger(BureauReportEnvelopeConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BureauReportExecutionService executionService;

    public BureauReportEnvelopeConsumer(BureauReportExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public String name() {
        return "bureau-report";
    }

    @Override
    public String topic() {
        return EnvelopeTopics.BUREAU;
    }

    @Override
    public void consume(CipherEnvelope envelope, String payloadJson) {
        JsonNode node;
        try {
            node = MAPPER.readTree(payloadJson == null ? "" : payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("征信信封载荷不是合法 JSON", e);
        }
        String kind = node.path("kind").asText("");
        String traceId = node.path("traceId").asText("");
        long loanId = node.path("loanId").asLong(0L);
        if (loanId <= 0) {
            throw new IllegalArgumentException("征信信封载荷缺 loanId");
        }
        if ("SETTLEMENT".equals(kind)) {
            var result = executionService.executeSettlement(loanId, traceId);
            log.info("密文信封结清报送完成 loanId={} reportId={} outcome={} envelopeId={}",
                    loanId, result.reportId(), result.outcome(), envelope.envelopeId());
            return;
        }
        String targetBizDate = node.path("targetBizDate").asText("");
        var row = new BureauTriggerPort.TriggerRow(
                node.path("triggerId").asLong(0L),
                loanId,
                node.path("dpdStage").asText(""),
                node.path("triggerType").asText(""),
                node.path("consentId").asLong(0L),
                targetBizDate.isBlank() ? null : LocalDate.parse(targetBizDate),
                node.path("status").asText(""));
        var result = executionService.executeTrigger(row, traceId);
        log.info("密文信封逾期报送完成 loanId={} reportId={} outcome={} envelopeId={}",
                loanId, result.reportId(), result.outcome(), envelope.envelopeId());
    }
}
