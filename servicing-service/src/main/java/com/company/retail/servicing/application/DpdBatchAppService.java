package com.company.retail.servicing.application;

import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.servicing.domain.DpdCalculator;
import com.company.retail.servicing.domain.DpdStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DPD 快照批处理用例（R05 §5「核销 + 余额 + DPD」的 DPD 侧；表 dpd_snapshots / collection_cases）。
 *
 * <ul>
 *   <li>uk_dpd_loan_date (loan_id, biz_date) 幂等：同日重跑不产生第二份快照（A28）；</li>
 *   <li>分层变化（含首次逾期）发 {@code DPD_CHANGED}（聚合键 loanId，payload 过白名单守卫）；</li>
 *   <li>逾期（stage != CURRENT）且无在途案件 → 开催收案件（uk_coll_loan_open 部分唯一兜底）；</li>
 *   <li>batch_runs 留痕（job_name=DPD_SNAPSHOT，uk_batch_job_date 可重跑）；</li>
 *   <li>审计 append-only（actor=SYSTEM / 触发人）。</li>
 * </ul>
 */
public class DpdBatchAppService {

    private static final Logger log = LoggerFactory.getLogger(DpdBatchAppService.class);

    public static final String JOB_NAME = "DPD_SNAPSHOT";

    private final LoanScheduleQueryPort loanScheduleQueryPort;
    private final DpdSnapshotRepository snapshotRepository;
    private final CollectionCaseRepository caseRepository;
    private final BatchRunRepository batchRunRepository;
    private final AuditWriter auditWriter;
    private final OutboxPublisher outboxPublisher;
    private final DpdCalculator calculator;

    public DpdBatchAppService(LoanScheduleQueryPort loanScheduleQueryPort,
                              DpdSnapshotRepository snapshotRepository,
                              CollectionCaseRepository caseRepository,
                              BatchRunRepository batchRunRepository,
                              AuditWriter auditWriter,
                              OutboxPublisher outboxPublisher,
                              DpdCalculator calculator) {
        this.loanScheduleQueryPort = loanScheduleQueryPort;
        this.snapshotRepository = snapshotRepository;
        this.caseRepository = caseRepository;
        this.batchRunRepository = batchRunRepository;
        this.auditWriter = auditWriter;
        this.outboxPublisher = outboxPublisher;
        this.calculator = calculator;
    }

    /** 执行一轮 DPD 快照（幂等可重跑）。 */
    @Transactional
    public BatchSummary run(LocalDate bizDate, String actor, String traceId) {
        batchRunRepository.start(JOB_NAME, bizDate, traceId);
        try {
            BatchSummary summary = doRun(bizDate, actor, traceId);
            batchRunRepository.finish(JOB_NAME, bizDate, "SUCCESS");
            log.info("DPD 快照完成 bizDate={} loans={} snapshots={} events={} casesOpened={} traceId={}",
                    bizDate, summary.loanCount(), summary.snapshotCount(),
                    summary.eventCount(), summary.casesOpened(), traceId);
            return summary;
        } catch (RuntimeException e) {
            batchRunRepository.finish(JOB_NAME, bizDate, "FAILED");
            throw e;
        }
    }

    private BatchSummary doRun(LocalDate bizDate, String actor, String traceId) {
        Map<Long, List<DpdCalculator.ScheduleItemView>> itemsByLoan = new HashMap<>();
        for (LoanScheduleQueryPort.ItemView item : loanScheduleQueryPort.loadScheduleItems()) {
            itemsByLoan.computeIfAbsent(item.loanId(), k -> new ArrayList<>())
                    .add(new DpdCalculator.ScheduleItemView(item.itemId(), item.dueDate(), item.status()));
        }

        List<LoanScheduleQueryPort.LoanView> loans = loanScheduleQueryPort.loadLoansForDpd();
        int snapshots = 0;
        int events = 0;
        int casesOpened = 0;

        for (LoanScheduleQueryPort.LoanView loan : loans) {
            DpdCalculator.DpdResult result =
                    calculator.compute(itemsByLoan.getOrDefault(loan.loanId(), List.of()), bizDate);

            // 分层变化检测先于落库：prev 是「今日之前」的最近快照
            var previous = snapshotRepository.findLatestBefore(loan.loanId(), bizDate);
            boolean inserted = snapshotRepository.insert(
                    loan.loanId(), bizDate, result.dpdDays(), result.stage().name(), traceId);
            if (!inserted) {
                continue;   // 同日重跑：快照已存在，不重复发事件/开案件（幂等）
            }
            snapshots++;

            boolean stageChanged = result.stage() != DpdStage.CURRENT
                    && (previous.isEmpty() || !previous.get().stage().equals(result.stage().name()));
            if (stageChanged) {
                publishDpdChanged(loan.loanId(), result, bizDate, traceId);
                events++;
            }
            if (result.stage() != DpdStage.CURRENT
                    && caseRepository.findOpenByLoan(loan.loanId()).isEmpty()
                    && caseRepository.insert(loan.loanId(), result.stage().name(), traceId)) {
                casesOpened++;
            }
        }

        BatchSummary summary = new BatchSummary(bizDate, loans.size(), snapshots, events, casesOpened);
        Map<String, Object> auditSnapshot = new LinkedHashMap<>();
        auditSnapshot.put("jobName", JOB_NAME);
        auditSnapshot.put("bizDate", bizDate.toString());
        auditSnapshot.put("loanCount", summary.loanCount());
        auditSnapshot.put("snapshotCount", summary.snapshotCount());
        auditSnapshot.put("eventCount", summary.eventCount());
        auditSnapshot.put("casesOpened", summary.casesOpened());
        auditWriter.write(actor, actorSubjectOf(actor), "DPD_BATCH_RUN", "BATCH", JOB_NAME,
                auditSnapshot, traceId);
        return summary;
    }

    private static String actorSubjectOf(String actor) {
        return "SYSTEM".equalsIgnoreCase(actor) ? "SYSTEM" : "STAFF";
    }

    /** DPD_CHANGED（R09 §5.1 聚合键 loanId；payload 只含白名单键，禁 PII——INV-4）。 */
    private void publishDpdChanged(long loanId, DpdCalculator.DpdResult result,
                                   LocalDate bizDate, String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loanId);
        payload.put("stage", result.stage().name());
        payload.put("status", result.stage().name());
        payload.put("bizDate", bizDate.toString());
        payload.put("source", "AUTO");
        payload.put("occurredAt", OffsetDateTime.now().toString());
        payload.put("schemaVersion", "1.0");
        payload.put("traceId", traceId);
        try {
            outboxPublisher.publish(OutboxPublisher.DPD_CHANGED, String.valueOf(loanId),
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("DPD_CHANGED payload 序列化失败", e);
        }
    }

    /** 批处理摘要（web 层返回；不含 PII）。 */
    public record BatchSummary(LocalDate bizDate, int loanCount, int snapshotCount,
                               int eventCount, int casesOpened) {
    }
}
