package com.company.retail.core.domain;

/**
 * 债权聚合根（A-9，R04 §2/§3.4）。状态变更只走本类方法（R00 §5：禁止直接 UPDATE ... SET status）。
 *
 * <p>不变式 INV-2：{@code balance ≡ principal + fee + interest_accrued − 已核销 − 已豁免}（精确相等）。
 * 不变式 I-1：一份合同只允许一个成功代付（uk_disb_loan + 本类守卫双兜底）。
 * 金额一律整数日元（long，R00 §5），禁止 double/float。
 * 终态四分（B-7）：SETTLED / EARLY_SETTLED / WRITTEN_OFF / CANCELLED 互斥。</p>
 */
public class Loan {

    private final Long loanId;
    private final long contractId;
    private LoanStatus status;
    private final long principal;
    private final long feeAmount;
    private long interestAccrued;
    private long balance;
    private java.time.Instant disbursedAt;
    private java.time.Instant settledAt;
    private String settlementType;
    private long waiverAmount;
    private long settlementFee;
    private final int version;

    private Loan(Long loanId, long contractId, LoanStatus status, long principal, long feeAmount,
                 long interestAccrued, long balance, java.time.Instant disbursedAt,
                 java.time.Instant settledAt, String settlementType,
                 long waiverAmount, long settlementFee, int version) {
        if (principal < 0 || feeAmount < 0 || interestAccrued < 0 || balance < 0
                || waiverAmount < 0 || settlementFee < 0) {
            throw new IllegalArgumentException("金额必须为非负整数日元");
        }
        this.loanId = loanId;
        this.contractId = contractId;
        this.status = status;
        this.principal = principal;
        this.feeAmount = feeAmount;
        this.interestAccrued = interestAccrued;
        this.balance = balance;
        this.disbursedAt = disbursedAt;
        this.settledAt = settledAt;
        this.settlementType = settlementType;
        this.waiverAmount = waiverAmount;
        this.settlementFee = settlementFee;
        this.version = version;
    }

    /** 建立债权（合同生效后的账务前半起点）。初始余额 = 本金 + 费用（INV-2 口径）。 */
    public static Loan create(long contractId, long principal, long feeAmount) {
        return new Loan(null, contractId, LoanStatus.PENDING_DISBURSEMENT,
                principal, feeAmount, 0, principal + feeAmount, null, null, null, 0, 0, 0);
    }

    /** repository 复原。 */
    public static Loan restore(Long loanId, long contractId, LoanStatus status, long principal,
                               long feeAmount, long interestAccrued, long balance,
                               java.time.Instant disbursedAt, java.time.Instant settledAt,
                               String settlementType, long waiverAmount, long settlementFee,
                               int version) {
        return new Loan(loanId, contractId, status, principal, feeAmount, interestAccrued, balance,
                disbursedAt, settledAt, settlementType, waiverAmount, settlementFee, version);
    }

    /** 代付守卫（INV-1）：只有未代付或可重放的失败态才允许发起代付指令。 */
    public void assertDisbursementAllowed() {
        if (status == LoanStatus.PENDING_DISBURSEMENT || status == LoanStatus.DISBURSE_FAILED) {
            return;
        }
        if (status == LoanStatus.DISBURSING || status == LoanStatus.ACTIVE
                || status == LoanStatus.DELINQUENT) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_LOAN_002,
                    "重复代付（INV-1：一份合同只允许一次成功代付）",
                    java.util.Map.of("loanStatus", status.name()));
        }
        throw new IllegalStateException("债权状态 " + status + " 不允许发起代付");
    }

    /** PENDING_DISBURSEMENT / DISBURSE_FAILED → DISBURSING（守卫：Contract=EFFECTIVE 由 G8 门禁在事务外判定）。 */
    public void startDisbursement() {
        if (!status.canTransitionTo(LoanStatus.DISBURSING)) {
            throw new IllegalStateException("债权状态机不允许 " + status + " → DISBURSING");
        }
        this.status = LoanStatus.DISBURSING;
    }

    /** DISBURSING → ACTIVE（付款回调与债权状态迁移同一本地事务，R05 §5 S7–S8）。 */
    public void completeDisbursement(java.time.Instant paidAt) {
        if (!status.canTransitionTo(LoanStatus.ACTIVE)) {
            throw new IllegalStateException("债权状态机不允许 " + status + " → ACTIVE");
        }
        this.status = LoanStatus.ACTIVE;
        this.disbursedAt = paidAt;
    }

    /** DISBURSING → DISBURSE_FAILED（失败可重放一次口径，不自动无限重试）。 */
    public void markDisburseFailed() {
        if (!status.canTransitionTo(LoanStatus.DISBURSE_FAILED)) {
            throw new IllegalStateException("债权状态机不允许 " + status + " → DISBURSE_FAILED");
        }
        this.status = LoanStatus.DISBURSE_FAILED;
    }

    public void cancel() {
        if (!status.canTransitionTo(LoanStatus.CANCELLED)) {
            throw new IllegalStateException("债权状态机不允许 " + status + " → CANCELLED");
        }
        this.status = LoanStatus.CANCELLED;
    }

    /** 还款计划生成：应计利息入账并计入余额（INV-2 的 interest_accrued 侧）。 */
    public void accrueInterest(long totalInterestDue) {
        if (totalInterestDue < 0) {
            throw new IllegalArgumentException("应计利息必须为非负整数日元");
        }
        this.interestAccrued = totalInterestDue;
        this.balance += totalInterestDue;
    }

    /** 核销守卫：只有存续态可接收入金核销（终态 / 在途代付一律拒绝，RF_PAY_001/VALID_002 语义）。 */
    public void assertAllocationAllowed() {
        if (status == LoanStatus.ACTIVE || status == LoanStatus.DELINQUENT) {
            return;
        }
        throw new com.company.platform.exception.BusinessException(
                com.company.retail.domain.exception.RFErrorCode.RF_VALID_002,
                "债权状态不允许入金核销",
                java.util.Map.of("loanStatus", status.name()));
    }

    /**
     * 入金核销扣减余额（费用/利息/本金的拆分在 {@code PaymentAllocationPlanner}，本方法只收总扣减额，
     * 保证调用点无论核销顺序如何都精确走同一余额口径）。
     */
    public void applyAllocation(long totalAllocated) {
        if (totalAllocated < 0) {
            throw new IllegalArgumentException("核销金额必须为非负整数日元");
        }
        if (totalAllocated > balance) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_PAY_002,
                    "超额入金（X-8：转工单/退款，不自动分摊）",
                    java.util.Map.of("allocated", String.valueOf(totalAllocated),
                            "balance", String.valueOf(balance)));
        }
        this.balance -= totalAllocated;
    }

    /** 结清守卫（G10）：末回核销完成、无未决异议（上游断言）且余额精确为 0 才允许进入终态。 */
    public void assertSettlementAllowed() {
        assertAllocationAllowed();
        if (balance != 0) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_002,
                    "余额未精确为 0，不得进入结清终态（G10）",
                    java.util.Map.of("balance", String.valueOf(balance)));
        }
    }

    /** 结清 → SETTLED（全部结清，settlement_type=NORMAL）或 EARLY_SETTLED（提前结清，EARLY）。B-7 终态互斥。 */
    public void settle(boolean early, java.time.Instant settledAt) {
        LoanStatus target = early ? LoanStatus.EARLY_SETTLED : LoanStatus.SETTLED;
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("债权状态机不允许 " + status + " → " + target);
        }
        this.status = target;
        this.settledAt = settledAt;
        this.settlementType = early ? "EARLY" : "NORMAL";
    }

    /** 落定结清三要素（C-10：减免额/事务手续费为挡板输入；EARLY 时与结算调整流水一致）。 */
    public void recordSettlementTerms(long waiverAmount, long settlementFee) {
        if (waiverAmount < 0 || settlementFee < 0) {
            throw new IllegalArgumentException("减免额/手续费必须为非负整数日元");
        }
        this.waiverAmount = waiverAmount;
        this.settlementFee = settlementFee;
    }

    /** 结清手续费增收（先计提再随结清入金核销，保证 INV-2 两侧同额浮动）。 */
    public void levySettlementFee(long fee) {
        if (fee < 0) {
            throw new IllegalArgumentException("手续费必须为非负整数日元");
        }
        this.settlementFee = fee;
        this.balance += fee;
    }

    /** 豁免（费用/利息/本金减免）：余额侧扣减，与 settlement_adjustments 流水一致（INV-2）。 */
    public void applyWaiver(long totalWaived) {
        if (totalWaived < 0) {
            throw new IllegalArgumentException("豁免额必须为非负整数日元");
        }
        if (totalWaived > balance) {
            throw new IllegalStateException("豁免额不得超过当前余额");
        }
        this.balance -= totalWaived;
        this.waiverAmount += totalWaived;
    }

    /** DELINQUENT → ACTIVE（入金补救）。 */
    public void cureFromDelinquent() {
        if (!status.canTransitionTo(LoanStatus.ACTIVE)) {
            throw new IllegalStateException("债权状态机不允许 " + status + " → ACTIVE");
        }
        this.status = LoanStatus.ACTIVE;
    }

    public Long loanId() {
        return loanId;
    }

    public long contractId() {
        return contractId;
    }

    public LoanStatus status() {
        return status;
    }

    public long principal() {
        return principal;
    }

    public long feeAmount() {
        return feeAmount;
    }

    public long interestAccrued() {
        return interestAccrued;
    }

    public long balance() {
        return balance;
    }

    public java.time.Instant disbursedAt() {
        return disbursedAt;
    }

    public java.time.Instant settledAt() {
        return settledAt;
    }

    public String settlementType() {
        return settlementType;
    }

    public long waiverAmount() {
        return waiverAmount;
    }

    public long settlementFee() {
        return settlementFee;
    }

    public int version() {
        return version;
    }
}
