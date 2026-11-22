package com.company.retail.underwriting.application;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.underwriting.domain.UnderwritingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * 征信查询完成事件消费方（BC-4 → BC-3，R04 §6）。
 *
 * <p>消费三铁律（R09 §5.4）：幂等（重复投递时状态机守卫天然挡住——已在目标状态直接跳过）；
 * 不吞异常（失败交回调度器重试/DEAD）；<b>不跨服务调用</b>（只读本服务案件表做本地状态推进）。</p>
 */
public class InquiryCompletedConsumer implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InquiryCompletedConsumer.class);

    /** 消费幂等键的一半：约定用服务名（R09 §5.4）。 */
    public static final String CONSUMER_NAME = "underwriting-service";

    private final UnderwritingCaseRepository caseRepository;

    public InquiryCompletedConsumer(UnderwritingCaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(OutboxPublisher.CREDIT_INQUIRY_COMPLETED);
    }

    @Override
    public void consume(DomainEvent event) {
        Long caseId = event.longField("caseId");
        if (caseId == null) {
            // 契约事件必带 caseId（R09 §5.2 白名单），缺失属上游编程错误，不吞——交回调度器
            throw new IllegalStateException("CREDIT_INQUIRY_COMPLETED payload 缺少 caseId: " + event.payload());
        }
        String inquiryStatus = event.textField("status");
        var testCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalStateException(
                        "CREDIT_INQUIRY_COMPLETED 指向不存在的案件 caseId=" + caseId));

        // 幂等：重放时案件已在目标状态，直接跳过（不抛异常、不重复推进）
        if (testCase.getStatus() != UnderwritingStatus.INQUIRY_IN_PROGRESS) {
            log.info("事件重放或状态已推进，跳过 caseId={} current={} eventId={}",
                    caseId, testCase.getStatus(), event.eventId());
            return;
        }
        if ("COMPLETED".equals(inquiryStatus)) {
            testCase.completeInquiry();
        } else if ("FAILED".equals(inquiryStatus)) {
            testCase.failInquiry();
        } else {
            throw new IllegalStateException("未知的征信查询状态 status=" + inquiryStatus);
        }
        caseRepository.update(testCase);
        log.info("审查案件随征信事件推进 caseId={} → {} eventId={}",
                caseId, testCase.getStatus(), event.eventId());
    }
}
