package com.company.retail.bureau.application;

import com.company.retail.bureau.domain.InquiryStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * bureau 测试内存仓储 + 记录型 outbox（与 RfJdbcOutboxPublisher 同样的
 * EventPayloadGuard 发布前校验语义，单测不连库即可验证 INV-4）。
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** 内存查询留痕仓储。 */
    public static class InMemoryInquiryRepository implements CreditInquiryRepository {
        private final Map<Long, InquiryRow> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(9000);

        @Override
        public long insert(long caseId, long consentId, String bureau, String purpose, String traceId) {
            long id = seq.incrementAndGet();
            store.put(id, new InquiryRow(id, caseId, consentId, bureau, purpose, "REQUESTED"));
            return id;
        }

        @Override
        public boolean markFinished(long inquiryId, InquiryStatus status, String resultSnapshotRef,
                                    long costAmount, String traceId) {
            InquiryRow row = store.get(inquiryId);
            if (row == null) {
                return false;
            }
            store.put(inquiryId, new InquiryRow(row.inquiryId(), row.caseId(), row.consentId(),
                    row.bureau(), row.purpose(), status.name()));
            return true;
        }

        @Override
        public Optional<InquiryRow> findById(long inquiryId) {
            return Optional.ofNullable(store.get(inquiryId));
        }

        @Override
        public int countFailed(long caseId, String bureau) {
            return (int) store.values().stream()
                    .filter(r -> r.caseId() == caseId && r.bureau().equals(bureau) && "FAILED".equals(r.status()))
                    .count();
        }
    }

    /** 内存报送留痕仓储（uk_report_loan_trigger 唯一语义）。 */
    public static class InMemoryReportRepository implements BureauReportRepository {
        private final Map<Long, ReportRow> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(8000);

        @Override
        public boolean insert(long loanId, long consentId, String bureau, String trigger, String traceId) {
            boolean exists = store.values().stream()
                    .anyMatch(r -> r.loanId() == loanId && r.trigger().equals(trigger));
            if (exists) {
                return false;
            }
            long id = seq.incrementAndGet();
            store.put(id, new ReportRow(id, loanId, consentId, bureau, trigger, "PENDING"));
            return true;
        }

        @Override
        public boolean markFinished(long reportId, String status, String payloadRef, String responseRef,
                                    String traceId) {
            ReportRow row = store.get(reportId);
            if (row == null) {
                return false;
            }
            store.put(reportId, new ReportRow(row.reportId(), row.loanId(), row.consentId(),
                    row.bureau(), row.trigger(), status));
            return true;
        }

        @Override
        public Optional<ReportRow> findById(long reportId) {
            return Optional.ofNullable(store.get(reportId));
        }

        @Override
        public Optional<ReportRow> findByLoanAndTrigger(long loanId, String trigger) {
            return store.values().stream()
                    .filter(r -> r.loanId() == loanId && r.trigger().equals(trigger))
                    .findFirst();
        }
    }

    /** 内存异议工单仓储。 */
    public static class InMemoryDisputeRepository implements BureauDisputeRepository {
        private final Map<Long, DisputeRow> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(7000);

        @Override
        public long insert(String customerRef, String bureau, String type,
                           java.time.OffsetDateTime dueAt, String traceId) {
            long id = seq.incrementAndGet();
            store.put(id, new DisputeRow(id, customerRef, bureau, type, dueAt, "OPEN"));
            return id;
        }

        @Override
        public Optional<DisputeRow> findById(long disputeId) {
            return Optional.ofNullable(store.get(disputeId));
        }

        @Override
        public boolean markResolved(long disputeId, String resolutionRef, String traceId) {
            DisputeRow row = store.get(disputeId);
            if (row == null) {
                return false;
            }
            store.put(disputeId, new DisputeRow(row.disputeId(), row.customerRef(), row.bureau(),
                    row.type(), row.dueAt(), "RESOLVED"));
            return true;
        }
    }

    /** 内存报文留痕仓储（append-only）。 */
    public static class InMemoryMessageLogRepository implements BureauMessageLogRepository {
        public record Entry(String direction, String bureau, String bizRef, String messageText) {
        }

        public final List<Entry> entries = new ArrayList<>();

        @Override
        public void append(String direction, String bureau, String bizRef, String messageText, String traceId) {
            entries.add(new Entry(direction, bureau, bizRef, messageText));
        }

        @Override
        public long countByBizRef(String bizRef) {
            return entries.stream().filter(e -> e.bizRef().equals(bizRef)).count();
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
            com.company.retail.common.contract.EventPayloadGuard.validate(eventType, payload);
            published.add(new PublishedEvent(eventType, aggregateKey, payload));
        }
    }
}
