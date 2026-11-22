package com.company.retail.servicing;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.servicing.application.AuditWriter;
import com.company.retail.servicing.application.BatchRunRepository;
import com.company.retail.servicing.application.BureauReportTriggerRepository;
import com.company.retail.servicing.application.CollectionActionRepository;
import com.company.retail.servicing.application.CollectionCaseRepository;
import com.company.retail.servicing.application.ConsentValidityGate;
import com.company.retail.servicing.application.DpdSnapshotRepository;
import com.company.retail.servicing.application.LoanScheduleQueryPort;
import com.company.retail.servicing.application.OutboxTrackingPort;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.platform.calendar.BusinessCalendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * servicing 测试内存夹具：仓储 / 查询端口 / 记录型 outbox（与 RfJdbcOutboxPublisher
 * 同样的 EventPayloadGuard 发布前校验语义，单测不连库即可验证 INV-4）。
 */
public final class ServicingTestFixtures {

    private ServicingTestFixtures() {
    }

    /** 记录型 outbox：发布前过白名单守卫（与生产 RfJdbcOutboxPublisher 同一校验语义）。 */
    public static class RecordingOutboxPublisher implements OutboxPublisher {
        public record Published(String eventType, String aggregateKey, String payload) {
        }

        public final List<Published> published = new ArrayList<>();

        @Override
        public void publish(String eventType, String aggregateKey, String payload) {
            EventPayloadGuard.validate(eventType, payload);
            published.add(new Published(eventType, aggregateKey, payload));
        }
    }

    /** 内存 DPD 快照仓储（uk_dpd_loan_date 唯一语义）。 */
    public static class InMemorySnapshotRepository implements DpdSnapshotRepository {
        private final Map<Long, SnapshotRow> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(6000);

        @Override
        public boolean insert(long loanId, LocalDate bizDate, int dpdDays, String stage, String traceId) {
            boolean exists = store.values().stream()
                    .anyMatch(r -> r.loanId() == loanId && r.bizDate().equals(bizDate));
            if (exists) {
                return false;
            }
            long id = seq.incrementAndGet();
            store.put(id, new SnapshotRow(id, loanId, bizDate, dpdDays, stage, "NATURAL"));
            return true;
        }

        @Override
        public Optional<SnapshotRow> findLatestBefore(long loanId, LocalDate bizDate) {
            return store.values().stream()
                    .filter(r -> r.loanId() == loanId && r.bizDate().isBefore(bizDate))
                    .reduce((a, b) -> a.bizDate().isAfter(b.bizDate()) ? a : b);
        }

        @Override
        public Optional<SnapshotRow> find(long loanId, LocalDate bizDate) {
            return store.values().stream()
                    .filter(r -> r.loanId() == loanId && r.bizDate().equals(bizDate))
                    .findFirst();
        }

        @Override
        public List<SnapshotRow> listByLoan(long loanId) {
            return store.values().stream().filter(r -> r.loanId() == loanId).toList();
        }
    }

    /** 内存催收案件仓储（status <> CLOSED 部分唯一语义）。 */
    public static class InMemoryCaseRepository implements CollectionCaseRepository {
        private final Map<Long, CaseRow> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(7000);

        @Override
        public boolean insert(long loanId, String dpdStage, String traceId) {
            boolean openExists = store.values().stream()
                    .anyMatch(r -> r.loanId() == loanId && !"CLOSED".equals(r.status()));
            if (openExists) {
                return false;
            }
            long id = seq.incrementAndGet();
            store.put(id, new CaseRow(id, loanId, dpdStage, "OPEN", null, 0));
            return true;
        }

        @Override
        public Optional<CaseRow> findById(long caseId) {
            return Optional.ofNullable(store.get(caseId));
        }

        @Override
        public Optional<CaseRow> findOpenByLoan(long loanId) {
            return store.values().stream()
                    .filter(r -> r.loanId() == loanId && !"CLOSED".equals(r.status()))
                    .findFirst();
        }

        @Override
        public boolean updateStatus(long caseId, String status, String assignedTo, boolean closed,
                                    long expectedVersion, String traceId) {
            CaseRow row = store.get(caseId);
            if (row == null || row.version() != expectedVersion) {
                return false;
            }
            store.put(caseId, new CaseRow(row.caseId(), row.loanId(), row.dpdStage(), status,
                    assignedTo, row.version() + 1));
            return true;
        }

        @Override
        public List<CaseRow> list(String statusOrNull) {
            return store.values().stream()
                    .filter(r -> statusOrNull == null || r.status().equals(statusOrNull))
                    .toList();
        }
    }

    /** 内存动作留痕仓储（append-only）。 */
    public static class InMemoryActionRepository implements CollectionActionRepository {
        private final List<ActionRow> rows = new ArrayList<>();
        private final AtomicLong seq = new AtomicLong(8000);

        @Override
        public void insert(long caseId, String actionType, String outcome, String note,
                           String actor, String actorSubject, String traceId) {
            rows.add(new ActionRow(seq.incrementAndGet(), caseId, actionType, outcome, note,
                    actor, actorSubject, traceId));
        }

        @Override
        public List<ActionRow> listByCase(long caseId) {
            return rows.stream().filter(r -> r.caseId() == caseId).toList();
        }
    }

    /** 内存报送触发仓储（uk_brt_loan_type 唯一语义）。 */
    public static class InMemoryTriggerRepository implements BureauReportTriggerRepository {
        private final Map<Long, TriggerRow> store = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(9000);

        @Override
        public boolean insert(long loanId, String dpdStage, String triggerType, long consentId,
                              LocalDate targetBizDate, String traceId) {
            boolean exists = store.values().stream()
                    .anyMatch(r -> r.loanId() == loanId && r.triggerType().equals(triggerType));
            if (exists) {
                return false;
            }
            long id = seq.incrementAndGet();
            store.put(id, new TriggerRow(id, loanId, dpdStage, triggerType, consentId,
                    targetBizDate, "REGISTERED"));
            return true;
        }

        @Override
        public Optional<TriggerRow> findById(long triggerId) {
            return Optional.ofNullable(store.get(triggerId));
        }

        @Override
        public Optional<TriggerRow> findByLoanAndType(long loanId, String triggerType) {
            return store.values().stream()
                    .filter(r -> r.loanId() == loanId && r.triggerType().equals(triggerType))
                    .findFirst();
        }

        @Override
        public List<TriggerRow> list(String statusOrNull) {
            return store.values().stream()
                    .filter(r -> statusOrNull == null || r.status().equals(statusOrNull))
                    .toList();
        }

        @Override
        public boolean updateStatus(long triggerId, String status, String traceId) {
            TriggerRow row = store.get(triggerId);
            if (row == null) {
                return false;
            }
            store.put(triggerId, new TriggerRow(row.triggerId(), row.loanId(), row.dpdStage(),
                    row.triggerType(), row.consentId(), row.targetBizDate(), status));
            return true;
        }
    }

    /** 内存批处理留痕仓储（(job, bizDate) 唯一语义，重跑复用同一行）。 */
    public static class InMemoryBatchRunRepository implements BatchRunRepository {
        public record Key(String jobName, LocalDate bizDate) {
        }

        public final Map<Key, String> runs = new LinkedHashMap<>();

        @Override
        public void start(String jobName, LocalDate bizDate, String traceId) {
            runs.put(new Key(jobName, bizDate), "RUNNING");
        }

        @Override
        public void finish(String jobName, LocalDate bizDate, String status) {
            runs.put(new Key(jobName, bizDate), status);
        }
    }

    /** 内存审计（append-only，记录动作留痕断言用）。 */
    public static class RecordingAuditWriter implements AuditWriter {
        public record Entry(String actor, String actorSubject, String action, String targetType,
                            String targetId, Map<String, Object> afterSnapshot, String traceId) {
        }

        public final List<Entry> entries = new ArrayList<>();

        @Override
        public void write(String actor, String actorSubject, String action, String targetType,
                          String targetId, Map<String, Object> afterSnapshot, String traceId) {
            entries.add(new Entry(actor, actorSubject, action, targetType, targetId,
                    afterSnapshot, traceId));
        }
    }

    /** 假债权/期次数据源（与 JdbcLoanScheduleQueryPort 同契约：终态债权不出现在 loadLoansForDpd）。 */
    public static class StubLoanSchedulePort implements LoanScheduleQueryPort {
        private static final Set<String> TERMINAL = Set.of(
                "SETTLED", "EARLY_SETTLED", "WRITTEN_OFF", "CANCELLED");
        private final List<LoanView> loans = new ArrayList<>();
        private final List<ItemView> items = new ArrayList<>();

        public void addLoan(long loanId, String status) {
            loans.add(new LoanView(loanId, status));
        }

        public void addItem(long itemId, long loanId, LocalDate dueDate, String status) {
            items.add(new ItemView(itemId, loanId, dueDate, status));
        }

        @Override
        public List<LoanView> loadLoansForDpd() {
            return loans.stream()
                    .filter(l -> !TERMINAL.contains(l.status()))
                    .toList();
        }

        @Override
        public List<ItemView> loadScheduleItems() {
            return new ArrayList<>(items);
        }
    }

    /** 同意门禁 stub（fail-closed 测试用）。 */
    public static class StubConsentGate implements ConsentValidityGate {
        private final Set<Long> validConsents = new HashSet<>();

        public void grant(long consentId) {
            validConsents.add(consentId);
        }

        public void revoke(long consentId) {
            validConsents.remove(consentId);
        }

        @Override
        public boolean isValid(long consentId) {
            return validConsents.contains(consentId);
        }
    }

    /** outbox 跟踪 stub。 */
    public static class StubOutboxTrackingPort implements OutboxTrackingPort {
        private final Map<String, String> statuses = new LinkedHashMap<>();

        public void put(String eventType, String aggregateKey, String status) {
            statuses.put(eventType + "|" + aggregateKey, status);
        }

        @Override
        public Optional<String> latestEventStatus(String eventType, String aggregateKey) {
            return Optional.ofNullable(statuses.get(eventType + "|" + aggregateKey));
        }
    }

    /** 营业日历 stub：周末非营业日 + 自定义假日。 */
    public static class StubBusinessCalendar implements BusinessCalendar {
        private final Set<LocalDate> holidays = new HashSet<>();

        public void addHoliday(LocalDate date) {
            holidays.add(date);
        }

        @Override
        public boolean isBusinessDay(LocalDate date) {
            DayOfWeek dow = date.getDayOfWeek();
            return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(date);
        }

        @Override
        public LocalDate plusBusinessDays(LocalDate from, int n) {
            LocalDate cursor = from;
            int remaining = Math.abs(n);
            int step = n >= 0 ? 1 : -1;
            int guard = 0;
            while (remaining > 0 && guard++ < 3660) {
                cursor = cursor.plusDays(step);
                if (isBusinessDay(cursor)) {
                    remaining--;
                }
            }
            return cursor;
        }
    }
}
