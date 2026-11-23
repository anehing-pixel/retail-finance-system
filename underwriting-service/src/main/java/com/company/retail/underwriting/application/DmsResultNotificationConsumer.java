package com.company.retail.underwriting.application;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.underwriting.integration.HttpDmsOutboundAdapter;
import com.company.retail.underwriting.mapper.OutboundTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 审查决定 → DMS 结果通知消费方（I-02，R07 §4.4；W-SIM2）。
 *
 * <p>消费 UNDERWRITING_APPROVED / UNDERWRITING_DECLINED，向 DMS 集成商推送决定
 * （双模：outbound-url 空=挡板 no-op，语义与原人工导出一致）。推送失败<b>不吞也不重试</b>：
 * 落 {@code integration.outbound_tasks}（PENDING，人工补发路径——结果通知是咨询性副本，
 * 系统内权威状态在案件表，故不因 DMS 不可达阻断事件消费）。</p>
 */
public class DmsResultNotificationConsumer implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DmsResultNotificationConsumer.class);

    public static final String CONSUMER_NAME = "underwriting-service-dms";

    private final HttpDmsOutboundAdapter adapter;
    private final OutboundTaskMapper outboundTaskMapper;

    public DmsResultNotificationConsumer(HttpDmsOutboundAdapter adapter,
                                         OutboundTaskMapper outboundTaskMapper) {
        this.adapter = adapter;
        this.outboundTaskMapper = outboundTaskMapper;
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OutboxPublisher.UNDERWRITING_APPROVED, OutboxPublisher.UNDERWRITING_DECLINED);
    }

    @Override
    public void consume(DomainEvent event) {
        if (adapter == null) {
            // 挡板模式（outbound-url 空）：原语义——结果只落 outbox 人工导出，不外推
            return;
        }
        Long caseId = event.longField("caseId");
        Long applicationId = event.longField("applicationId");
        String decision = event.textField("status");
        if (caseId == null || decision == null) {
            throw new IllegalStateException("审查决定事件 payload 缺少 caseId/status: " + event.payload());
        }
        String dmsRef = adapter.sendResult(caseId, applicationId == null ? 0 : applicationId,
                decision, event.textField("reasonCode"), event.textField("traceId"));
        if (dmsRef == null) {
            // 咨询性副本推送失败：落 outbound_tasks 人工补发，不阻断事件流（R09 §5.4 例外声明）
            outboundTaskMapper.insertPending(String.valueOf(caseId), event.textField("traceId"));
            log.warn("DMS 结果通知失败，已落 outbound_tasks 人工补发 caseId={} decision={}", caseId, decision);
            return;
        }
        log.info("DMS 结果通知已受理 caseId={} decision={} dmsRef={}", caseId, decision, dmsRef);
    }
}
