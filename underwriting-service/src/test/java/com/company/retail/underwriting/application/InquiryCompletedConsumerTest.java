package com.company.retail.underwriting.application;

import com.company.platform.event.DomainEvent;
import com.company.retail.underwriting.application.TestFixtures.InMemoryCaseRepository;
import com.company.retail.underwriting.domain.UnderwritingCase;
import com.company.retail.underwriting.domain.UnderwritingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 征信查询完成事件消费单测（BC-4 → BC-3；消费三铁律：幂等 / 不吞异常 / 不跨服务调用）。
 */
class InquiryCompletedConsumerTest {

    private InMemoryCaseRepository caseRepository;
    private InquiryCompletedConsumer consumer;

    @BeforeEach
    void setUp() {
        caseRepository = new InMemoryCaseRepository();
        consumer = new InquiryCompletedConsumer(caseRepository);
    }

    private long caseInProgress() {
        UnderwritingCase c = UnderwritingCase.start(1001L, 5001L, "W4-DEFAULT");
        c.startInquiry(); // INQUIRY_IN_PROGRESS
        caseRepository.insert(c);
        return c.getCaseId();
    }

    private DomainEvent event(String payload) {
        return new DomainEvent(1L, "CREDIT_INQUIRY_COMPLETED", "9001", payload, Instant.now());
    }

    @Test
    void completedEvent_movesCaseToInquiryDone() {
        long caseId = caseInProgress();
        consumer.consume(event("{\"inquiryId\":9001,\"caseId\":" + caseId
                + ",\"status\":\"COMPLETED\",\"traceId\":\"t\"}"));
        assertEquals(UnderwritingStatus.INQUIRY_DONE,
                caseRepository.findById(caseId).orElseThrow().getStatus());
    }

    @Test
    void failedEvent_movesCaseToInquiryFailed() {
        long caseId = caseInProgress();
        consumer.consume(event("{\"inquiryId\":9001,\"caseId\":" + caseId
                + ",\"status\":\"FAILED\",\"traceId\":\"t\"}"));
        assertEquals(UnderwritingStatus.INQUIRY_FAILED,
                caseRepository.findById(caseId).orElseThrow().getStatus());
    }

    @Test
    void replay_isIdempotent_noThrowNoDoubleTransition() {
        // 铁律 1（幂等）：at-least-once 重放直接跳过
        long caseId = caseInProgress();
        DomainEvent e = event("{\"inquiryId\":9001,\"caseId\":" + caseId
                + ",\"status\":\"COMPLETED\",\"traceId\":\"t\"}");
        consumer.consume(e);
        assertEquals(UnderwritingStatus.INQUIRY_DONE,
                caseRepository.findById(caseId).orElseThrow().getStatus());
        assertDoesNotThrow(() -> consumer.consume(e)); // 重放：状态已推进 → 跳过
        assertEquals(UnderwritingStatus.INQUIRY_DONE,
                caseRepository.findById(caseId).orElseThrow().getStatus());
    }

    @Test
    void missingCaseId_throws_notSwallowed() {
        // 铁律 2（不吞异常）：坏事件交回调度器重试/DEAD
        assertThrows(IllegalStateException.class,
                () -> consumer.consume(event("{\"inquiryId\":9001,\"traceId\":\"t\"}")));
    }

    @Test
    void unknownInquiryStatus_throws_notSwallowed() {
        long caseId = caseInProgress();
        assertThrows(IllegalStateException.class,
                () -> consumer.consume(event("{\"inquiryId\":9001,\"caseId\":" + caseId
                        + ",\"status\":\"WEIRD\",\"traceId\":\"t\"}")));
    }

    @Test
    void consumerNameAndSubscription_followContract() {
        assertEquals("underwriting-service", consumer.name());
        assertEquals(java.util.Set.of("CREDIT_INQUIRY_COMPLETED"), consumer.eventTypes());
    }
}
