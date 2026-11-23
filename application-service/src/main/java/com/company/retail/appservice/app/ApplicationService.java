package com.company.retail.appservice.app;

import com.company.platform.exception.BusinessException;
import com.company.platform.idempotency.IdempotencyGuard;
import com.company.retail.appservice.domain.Application;
import com.company.retail.appservice.domain.ApplicationStatus;
import com.company.retail.appservice.domain.DuplicateApplicationDetector;
import com.company.retail.appservice.repo.ApplicationIdempotencyStore;
import com.company.retail.appservice.repo.ApplicationRepository;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.lock.DistributedLockManager;
import com.company.retail.common.lock.LockKeys;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 申请受理用例（A-4）：提交幂等（platform IdempotencyGuard + DB 唯一索引双兜底，
 * R09 §2/§6）→ 状态机推进 → APPLICATION_SUBMITTED 事件（payload 白名单，INV-4）。
 */
@Service
public class ApplicationService {

    private final ApplicationRepository applications;
    private final ApplicationIdempotencyStore idempotencyStore;
    private final OutboxPublisher outbox;
    private final DistributedLockManager locks;
    private final Duration duplicateWindow;
    private final Duration lockWait;

    public ApplicationService(ApplicationRepository applications,
                              ApplicationIdempotencyStore idempotencyStore,
                              OutboxPublisher outbox,
                              DistributedLockManager locks,
                              @Value("${retail.application.duplicate-window-hours:72}") long duplicateWindowHours,
                              @Value("${retail.lock.wait-time-ms:3000}") long lockWaitMs) {
        this.applications = applications;
        this.idempotencyStore = idempotencyStore;
        this.outbox = outbox;
        this.locks = locks;
        this.duplicateWindow = Duration.ofHours(duplicateWindowHours);
        this.lockWait = Duration.ofMillis(lockWaitMs);
    }

    public record SubmitCommand(long customerId, Long dealerPartnerId, String transactionScheme,
                                String channel, Long consentId, Application.Item vehicle) {
    }

    /**
     * 提交申请（受理，R05 §4 G1）：
     * ① 幂等（同键同指纹=重放返回首次结果；同键异指纹=RF_CONFLICT_001）
     * ② 领域提交守卫
     * ③ 分布式锁串行化同客户同 VIN 的并发受理（ADR-RF-015）
     * ④ 锁内重查去重窗口（RF_APP_001）→ 落库 + 发事件同一本地事务。
     */
    @Transactional
    public Result submit(SubmitCommand cmd, String idempotencyKey, String requestFingerprint, String traceId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "Idempotency-Key 头必填（R09 §2）"));
        }
        // ① 幂等守卫（机制层，指纹冲突即 RF_CONFLICT_001）
        var existing = IdempotencyGuard.checkOrThrow(
                idempotencyStore, idempotencyKey, requestFingerprint, RFErrorCode.RF_CONFLICT_001);
        if (existing.isPresent()) {
            // 重放：返回首次受理结果，不重复执行业务
            Application prior = applications.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new BusinessException(RFErrorCode.RF_SYS_001,
                            Map.of("reason", "幂等记录存在但申请缺失")));
            return Result.of(prior, true);
        }

        // ② 领域提交守卫先行（必填项 / 双形态 / 同意 / 车辆齐备后才做去重与落库）
        Application agg = Application.submit(cmd.customerId(), cmd.dealerPartnerId(),
                cmd.transactionScheme(), cmd.channel(), cmd.consentId(), cmd.vehicle(), Instant.now());

        // ③ 并发窗口：分布式锁（ADR-RF-015）——同客户同 VIN 的并发受理串行化。
        //    锁在事务提交后释放（RedissonLockManager 的 afterCompletion），锁内重查去重窗口。
        return locks.callWithLock(
                LockKeys.applicationDedup(cmd.customerId(), cmd.vehicle().vin()), lockWait,
                () -> submitUnderLock(agg, cmd, idempotencyKey, requestFingerprint, traceId));
    }

    /**
     * 锁内受理：重复申请去重 → 落库 → 发事件（受理写入与事件发布同一本地事务）。
     *
     * <p>去重查询必须在拿到锁之后重做：锁外的查询看不到并发请求刚提交的申请，
     * 这正是「先查后插」竞态的成因（ADR-RF-015）。</p>
     */
    private Result submitUnderLock(Application agg, SubmitCommand cmd, String idempotencyKey,
                                    String requestFingerprint, String traceId) {
        // ④ 锁内先重查幂等键：并发同键请求里，后到者应返回首次受理结果（重放），
        //    而不是被去重窗口判成「重复申请」；异指纹同键仍是 RF_CONFLICT_001。
        Application priorByKey = applications.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (priorByKey != null) {
            return replayOrConflict(idempotencyKey, requestFingerprint);
        }

        // ⑤ 重复申请去重（同一人 + 同一車 + 窗口期；锁内重查）
        applications.findPriorDuplicateSubmittedAt(cmd.customerId(), cmd.vehicle().vin())
                .filter(priorAt -> DuplicateApplicationDetector.isDuplicate(priorAt, Instant.now(), duplicateWindow))
                .ifPresent(priorAt -> {
                    throw new BusinessException(RFErrorCode.RF_APP_001, Map.of(
                            "customerId", String.valueOf(cmd.customerId()),
                            "vin", cmd.vehicle().vin(),
                            "windowHours", String.valueOf(duplicateWindow.toHours())));
                });

        // ⑥ 落库 + 发事件同一本地事务
        String applicationNo = generateNo();
        long applicationId = applications.insert(agg, applicationNo, cmd.vehicle(),
                idempotencyKey, requestFingerprint, traceId);
        if (applicationId == 0) {
            // 同幂等键并发：uk_app_idempotency 唯一索引兜底（ON CONFLICT DO NOTHING），
            // 冲突方按请求指纹判定「重放」或「冲突」，不落车辆项、不发事件。
            return replayOrConflict(idempotencyKey, requestFingerprint);
        }
        agg.assignIdentity(applicationId, applicationNo);

        // APPLICATION_SUBMITTED（聚合键 applicationId；payload 只带业务主键，禁 PII）
        RfEventPayloads.publish(outbox, OutboxPublisher.APPLICATION_SUBMITTED,
                "applicationId", String.valueOf(applicationId), traceId,
                Map.of("status", agg.getStatus().name(),
                        "scheme", agg.getTransactionScheme(),
                        "idempotencyKey", idempotencyKey));
        return Result.of(agg, false);
    }

    /** 并发同幂等键：同指纹 = 重放（返回首次受理结果）；异指纹 = RF_CONFLICT_001。 */
    private Result replayOrConflict(String idempotencyKey, String requestFingerprint) {
        Application prior = applications.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_SYS_001,
                        Map.of("reason", "APPLICATION_INSERT_CONFLICT_WITHOUT_PRIOR_ROW")));
        String priorFingerprint = applications.findFingerprintByIdempotencyKey(idempotencyKey).orElse(null);
        if (priorFingerprint == null || !priorFingerprint.equals(requestFingerprint)) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    Map.of("reason", "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD"));
        }
        return Result.of(prior, true);
    }

    /** 撤回（客户动作；SUBMITTED → WITHDRAWN 终态）。 */
    @Transactional
    public void withdraw(long applicationId, String traceId) {
        Application agg = applications.findById(applicationId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "申请不存在", "applicationId", String.valueOf(applicationId))));
        agg.withdraw();
        applications.updateStatus(applicationId, agg.getStatus());
    }

    /** 系统初检 → 补件态（SUBMITTED → DOCUMENTS_PENDING）。 */
    @Transactional
    public void requestDocuments(long applicationId, String traceId) {
        Application agg = applications.findById(applicationId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "申请不存在", "applicationId", String.valueOf(applicationId))));
        agg.requestDocuments();
        applications.updateStatus(applicationId, agg.getStatus());
    }

    /** 领域推进（由后续用例调用：文件受理/审查决定等，目标受 R04 §3.1 表约束）。 */
    @Transactional
    public void transition(long applicationId, ApplicationStatus target, String traceId) {
        Application agg = applications.findById(applicationId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "申请不存在", "applicationId", String.valueOf(applicationId))));
        agg.transitionTo(target);
        applications.updateStatus(applicationId, agg.getStatus());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long applicationId) {
        Application agg = applications.findById(applicationId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "申请不存在", "applicationId", String.valueOf(applicationId))));
        return Map.of(
                "applicationId", agg.getApplicationId(),
                "applicationNo", agg.getApplicationNo(),
                "customerId", agg.getCustomerId(),
                "dealerPartnerId", String.valueOf(agg.getDealerPartnerId()),
                "status", agg.getStatus().name(),
                "transactionScheme", agg.getTransactionScheme(),
                "channel", agg.getChannel());
    }

    private String generateNo() {
        String ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(java.time.ZoneOffset.ofHours(9)).format(Instant.now());
        return "AP" + ts + String.format("%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(10000));
    }

    /** 受理结果：replayed=true 表示幂等重放（返回首次结果）。 */
    public record Result(long applicationId, String applicationNo, ApplicationStatus status,
                         boolean replayed) {
        static Result of(Application agg, boolean replayed) {
            return new Result(agg.getApplicationId(), agg.getApplicationNo(), agg.getStatus(), replayed);
        }
    }
}
