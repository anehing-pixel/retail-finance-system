package com.company.retail.recon.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 对拍批量执行用例（R02 §8 INV-1~5；batch_runs 留痕可重跑幂等）。
 *
 * <ul>
 *   <li>batch_runs：同 (job_name, biz_date) 重跑复用同一行（A28，不产生第二份）；</li>
 *   <li>recon_diffs：同 (inv_code, entity_type, entity_id) 只留一行 OPEN（部分唯一索引兜底），
 *       重跑时已修复项 → RESOLVED，新差异 → OPEN，历史不删除；</li>
 *   <li>对拍判定口径：精确相等才无差异（R02 §8，不容差、不做舍入猜测）。</li>
 * </ul>
 */
public class ReconRunAppService {

    private static final Logger log = LoggerFactory.getLogger(ReconRunAppService.class);

    public static final String JOB_NAME = "RECON_INV";

    private final ReconQueryPort queryPort;
    private final ReconDiffRepository diffRepository;
    private final ReconBatchRunRepository batchRunRepository;

    public ReconRunAppService(ReconQueryPort queryPort,
                              ReconDiffRepository diffRepository,
                              ReconBatchRunRepository batchRunRepository) {
        this.queryPort = queryPort;
        this.diffRepository = diffRepository;
        this.batchRunRepository = batchRunRepository;
    }

    /** 执行一轮全视点对拍（幂等可重跑）。 */
    public RunSummary run(String bizDate, String traceId) {
        batchRunRepository.start(JOB_NAME, bizDate, traceId);
        try {
            RunSummary summary = doRun(bizDate, traceId);
            batchRunRepository.finish(JOB_NAME, bizDate, "SUCCESS");
            log.info("对拍完成 bizDate={} findings={} openDiffs={} resolved={} traceId={}",
                    bizDate, summary.findings(), summary.openDiffs(), summary.resolvedDiffs(),
                    traceId);
            return summary;
        } catch (RuntimeException e) {
            batchRunRepository.finish(JOB_NAME, bizDate, "FAILED");
            throw e;
        }
    }

    private RunSummary doRun(String bizDate, String traceId) {
        List<ReconFinding> findings = queryPort.collectFindings();

        int upserted = 0;
        for (ReconFinding finding : findings) {
            if (diffRepository.upsertOpen(JOB_NAME, bizDate, finding.invCode(),
                    finding.entityType(), finding.entityId(), finding.detail(), traceId)) {
                upserted++;
            }
        }

        // 已修复：上一轮 OPEN、本轮未再检出 → RESOLVED（不物理删除）
        Set<String> currentKeys = new HashSet<>();
        for (ReconFinding f : findings) {
            currentKeys.add(key(f.invCode(), f.entityType(), f.entityId()));
        }
        int resolved = 0;
        for (ReconDiffRepository.OpenKey open : diffRepository.listOpenKeys()) {
            if (!currentKeys.contains(key(open.invCode(), open.entityType(), open.entityId()))) {
                if (diffRepository.resolve(open, traceId)) {
                    resolved++;
                }
            }
        }
        return new RunSummary(bizDate, findings.size(), upserted, resolved);
    }

    private static String key(String invCode, String entityType, String entityId) {
        return invCode + "|" + entityType + "|" + entityId;
    }

    public List<ReconDiffRepository.DiffRow> diffs(String invCodeOrNull, String statusOrNull) {
        return diffRepository.list(invCodeOrNull, statusOrNull);
    }

    public Optional<ReconDiffRepository.DiffRow> diff(long diffId) {
        return diffRepository.findById(diffId);
    }

    public List<ReconBatchRunRepository.RunRow> runs() {
        return batchRunRepository.list(null);
    }

    /** 执行摘要（web 层返回；不含 PII）。 */
    public record RunSummary(String bizDate, int findings, int openDiffs, int resolvedDiffs) {
    }
}
