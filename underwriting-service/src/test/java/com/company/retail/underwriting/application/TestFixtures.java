package com.company.retail.underwriting.application;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.underwriting.domain.AffordabilityInput;
import com.company.retail.underwriting.domain.AssessmentOutput;
import com.company.retail.underwriting.domain.UnderwritingCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试内存仓储 + 记录型 outbox（与 RfJdbcOutboxPublisher 同样的 EventPayloadGuard
 * 发布前校验语义，单测不连库即可验证 INV-4）。
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** 内存案件仓储。 */
    public static class InMemoryCaseRepository implements UnderwritingCaseRepository {
        private final Map<Long, UnderwritingCase> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(1000);

        @Override
        public void insert(UnderwritingCase testCase) {
            testCase.assignId(seq.incrementAndGet());
            store.put(testCase.getCaseId(), testCase);
        }

        @Override
        public Optional<UnderwritingCase> findById(long caseId) {
            return Optional.ofNullable(store.get(caseId));
        }

        @Override
        public boolean update(UnderwritingCase testCase) {
            store.put(testCase.getCaseId(), testCase);
            return true;
        }
    }

    /** 内存评估快照仓储。 */
    public static class InMemoryAssessmentRepository implements AffordabilityAssessmentRepository {
        private final Map<Long, AssessmentOutput> store = new LinkedHashMap<>();

        @Override
        public void insert(long caseId, AffordabilityInput input, AssessmentOutput output, String traceId) {
            store.put(caseId, output);
        }

        @Override
        public Optional<AssessmentOutput> findLatest(long caseId) {
            return Optional.ofNullable(store.get(caseId));
        }
    }

    /** 记录型 outbox：发布前执行与生产实现相同的 EventPayloadGuard 白名单校验。 */
    public static class RecordingOutboxPublisher implements com.company.retail.common.contract.OutboxPublisher {
        public record PublishedEvent(String eventType, String aggregateKey, String payload) {
        }

        public final List<PublishedEvent> published = new ArrayList<>();

        @Override
        public void publish(String eventType, String aggregateKey, String payload) {
            // 与 RfJdbcOutboxPublisher 一致：校验在写库前，非法事件绝不落库（INV-4）
            EventPayloadGuard.validate(eventType, payload);
            published.add(new PublishedEvent(eventType, aggregateKey, payload));
        }
    }
}
