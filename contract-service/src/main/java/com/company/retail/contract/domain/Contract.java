package com.company.retail.contract.domain;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 合同聚合根（A-8，R04 §2/§3.3）。状态变更只走本类方法，禁止绕过状态机直接改 status。
 *
 * <p><b>C-08 裁决口径</b>：法定书面文件 = 模板可配 + 交付留痕；冷静期 = 8 日占位参数，
 * 期间与例外待法务核实（核实不符改配置即生效）。<b>不实现书面文件效力判断与冷静期效力判断</b>——
 * 系统只做交付动作留痕与状态记录。</p>
 *
 * <p>零 Spring、可纯单测（R04 §7 U-4）。</p>
 */
public class Contract {

    /** 全系统 JST 业务口径（R00 §5）。 */
    public static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    private final Long contractId;
    private final long applicationId;
    private final long customerId;
    private final TransactionScheme scheme;
    private ContractStatus status;
    private java.time.Instant signedAt;
    private java.time.Instant effectiveAt;
    private LocalDate coolingOffUntil;
    private final int version;

    private Contract(Long contractId, long applicationId, long customerId, TransactionScheme scheme,
                     ContractStatus status, java.time.Instant signedAt, java.time.Instant effectiveAt,
                     LocalDate coolingOffUntil, int version) {
        this.contractId = contractId;
        this.applicationId = applicationId;
        this.customerId = customerId;
        this.scheme = scheme;
        this.status = status;
        this.signedAt = signedAt;
        this.effectiveAt = effectiveAt;
        this.coolingOffUntil = coolingOffUntil;
        this.version = version;
    }

    /** 新建合同（DRAFT）。 */
    public static Contract draft(long applicationId, long customerId, TransactionScheme scheme) {
        return new Contract(null, applicationId, customerId, scheme, ContractStatus.DRAFT,
                null, null, null, 0);
    }

    /** 复原（repository 载入用）。 */
    public static Contract restore(Long contractId, long applicationId, long customerId,
                                   TransactionScheme scheme, ContractStatus status,
                                   java.time.Instant signedAt, java.time.Instant effectiveAt,
                                   LocalDate coolingOffUntil, int version) {
        return new Contract(contractId, applicationId, customerId, scheme, status,
                signedAt, effectiveAt, coolingOffUntil, version);
    }

    /**
     * DRAFT → DOCUMENTS_DELIVERED：法定书面文件模板按交易形态选定（C-08 模板可配）。
     * 交付留痕本身落在 statutory_documents 行上；这里只确认「该交付的都交付了」。
     */
    public void markDocumentsDelivered(ContractPolicy policy, List<StatutoryDocument> documents,
                                       Clock clock) {
        assertTransition(ContractStatus.DOCUMENTS_DELIVERED);
        policy.assertAllRequiredDelivered(scheme, documents);
        this.status = ContractStatus.DOCUMENTS_DELIVERED;
    }

    /**
     * DOCUMENTS_DELIVERED → SIGNED，随后按 C-08 配置走 SIGNED → COOLING_OFF / EFFECTIVE（同一事务两段迁移）。
     *
     * <p>守卫（G7 / RF_CTR_001）：法定书面文件未交付不得签订。</p>
     *
     * @return 签订后是否进入冷静期（true = COOLING_OFF）
     */
    public boolean sign(ContractPolicy policy, List<StatutoryDocument> documents, Clock clock,
                        java.time.Instant signedAt) {
        assertTransition(ContractStatus.SIGNED);
        policy.assertAllRequiredDelivered(scheme, documents);
        this.signedAt = signedAt;
        this.status = ContractStatus.SIGNED;
        boolean coolingOff = policy.coolingOffApplicable(scheme);
        if (coolingOff) {
            // SIGNED → COOLING_OFF（C-08：8 日占位，参数可配）
            transition(ContractStatus.COOLING_OFF);
            this.coolingOffUntil = LocalDate.ofInstant(signedAt, JST)
                    .plusDays(policy.coolingOffDays());
        } else {
            // SIGNED → EFFECTIVE
            transition(ContractStatus.EFFECTIVE);
            this.effectiveAt = signedAt;
        }
        return coolingOff;
    }

    /**
     * COOLING_OFF → EFFECTIVE：冷静期占位流转。运营确认冷静期内未行使解约即生效；
     * <b>不实现冷静期效力判断</b>（C-08：期间与例外待法务核实）。
     */
    public void confirmCoolingOffExpired(Clock clock) {
        assertTransition(ContractStatus.EFFECTIVE);
        this.effectiveAt = java.time.Instant.now(clock);
        this.status = ContractStatus.EFFECTIVE;
    }

    /** COOLING_OFF / DOCUMENTS_DELIVERED → CANCELLED（解约）。终态，I-7。 */
    public void cancel() {
        assertTransition(ContractStatus.CANCELLED);
        this.status = ContractStatus.CANCELLED;
    }

    /** EFFECTIVE → TERMINATED（结清/解约后，由后续波次触发）。 */
    public void terminate() {
        assertTransition(ContractStatus.TERMINATED);
        this.status = ContractStatus.TERMINATED;
    }

    /** 合同是否已生效（G8 代付门禁判定口径，供 gate 端点消费）。 */
    public boolean isEffective() {
        return status == ContractStatus.EFFECTIVE;
    }

    /** 签订时的条款快照键值（append-only，落 terms_snapshot JSONB）。 */
    public java.util.Map<String, Object> termsSnapshot(ContractPolicy policy) {
        java.util.Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("scheme", scheme.name());
        snapshot.put("requiredDocTypes", String.join(",", policy.requiredDocTypes(scheme)));
        snapshot.put("coolingOffApplicable", policy.coolingOffApplicable(scheme));
        snapshot.put("coolingOffDays", policy.coolingOffApplicable(scheme) ? policy.coolingOffDays() : 0);
        return snapshot;
    }

    private void assertTransition(ContractStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("合同状态机不允许 " + status + " → " + target);
        }
    }

    private void transition(ContractStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("合同状态机不允许 " + status + " → " + target);
        }
        this.status = target;
    }

    public Long contractId() {
        return contractId;
    }

    public long applicationId() {
        return applicationId;
    }

    public long customerId() {
        return customerId;
    }

    public TransactionScheme scheme() {
        return scheme;
    }

    public ContractStatus status() {
        return status;
    }

    public java.time.Instant signedAt() {
        return signedAt;
    }

    public java.time.Instant effectiveAt() {
        return effectiveAt;
    }

    public LocalDate coolingOffUntil() {
        return coolingOffUntil;
    }

    public int version() {
        return version;
    }
}
