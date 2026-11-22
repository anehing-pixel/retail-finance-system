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

    /** 内存报送留痕仓储（uk_report_loan_trigger 唯一语义；V15 source 列语义）。 */
    public static class InMemoryReportRepository implements BureauReportRepository {
        final Map<Long, ReportRow> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(8000);

        @Override
        public boolean insert(long loanId, long consentId, String bureau, String trigger, String traceId) {
            return insertWithSource(loanId, consentId, bureau, trigger, "AUTO", traceId);
        }

        @Override
        public boolean insertWithSource(long loanId, long consentId, String bureau, String trigger,
                                        String source, String traceId) {
            boolean exists = store.values().stream()
                    .anyMatch(r -> r.loanId() == loanId && r.trigger().equals(trigger));
            if (exists) {
                return false;
            }
            long id = seq.incrementAndGet();
            store.put(id, new ReportRow(id, loanId, consentId, bureau, trigger, "PENDING", source));
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
                    row.bureau(), row.trigger(), status, row.source()));
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

    /** 内存报送触发登记（bureau 侧投影：读 + 状态推进）。 */
    public static class InMemoryBureauTriggerRepository implements BureauTriggerPort {
        public record Row(long triggerId, long loanId, String dpdStage, String triggerType,
                          long consentId, java.time.LocalDate targetBizDate, String status) {
        }

        public final Map<Long, Row> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(6000);

        public long insert(long loanId, String dpdStage, String triggerType, long consentId,
                           java.time.LocalDate targetBizDate) {
            long id = seq.incrementAndGet();
            store.put(id, new Row(id, loanId, dpdStage, triggerType, consentId, targetBizDate, "REGISTERED"));
            return id;
        }

        public void setStatus(long triggerId, String status) {
            Row row = store.get(triggerId);
            store.put(triggerId, new Row(row.triggerId(), row.loanId(), row.dpdStage(),
                    row.triggerType(), row.consentId(), row.targetBizDate(), status));
        }

        @Override
        public Optional<TriggerRow> findByLoanAndType(long loanId, String triggerType) {
            return store.values().stream()
                    .filter(r -> r.loanId() == loanId && r.triggerType().equals(triggerType))
                    .findFirst()
                    .map(r -> new TriggerRow(r.triggerId(), r.loanId(), r.dpdStage(),
                            r.triggerType(), r.consentId(), r.targetBizDate(), r.status()));
        }

        @Override
        public List<TriggerRow> findDue(java.time.LocalDate today) {
            return store.values().stream()
                    .filter(r -> "REGISTERED".equals(r.status()) && !r.targetBizDate().isAfter(today))
                    .sorted(java.util.Comparator.comparingLong(Row::triggerId))
                    .map(r -> new TriggerRow(r.triggerId(), r.loanId(), r.dpdStage(),
                            r.triggerType(), r.consentId(), r.targetBizDate(), r.status()))
                    .toList();
        }

        @Override
        public boolean updateStatus(long triggerId, String status) {
            Row row = store.get(triggerId);
            if (row == null) {
                return false;
            }
            setStatus(triggerId, status);
            return true;
        }
    }

    /** 内存批处理留痕（core.batch_runs 语义：start/finish 同键复用）。 */
    public static class RecordingBatchRunPort implements BureauBatchRunPort {
        public record Run(String jobName, java.time.LocalDate bizDate, String status) {
        }

        public final Map<String, Run> runs = new LinkedHashMap<>();

        @Override
        public void start(String jobName, java.time.LocalDate bizDate, String traceId) {
            runs.put(jobName + "|" + bizDate, new Run(jobName, bizDate, "RUNNING"));
        }

        @Override
        public void finish(String jobName, java.time.LocalDate bizDate, String status) {
            runs.put(jobName + "|" + bizDate, new Run(jobName, bizDate, status));
        }
    }

    /** 内存结清同意锚点（LOAN_SETTLED consentId 解析挡板）。 */
    public static class InMemoryConsentAnchor implements SettlementConsentAnchorPort {
        private final Map<Long, Long> consentByLoan = new LinkedHashMap<>();

        public void put(long loanId, long consentId) {
            consentByLoan.put(loanId, consentId);
        }

        @Override
        public Optional<Long> resolveConsentId(long loanId) {
            return Optional.ofNullable(consentByLoan.get(loanId));
        }
    }
}
