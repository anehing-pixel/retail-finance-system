package com.company.retail.core.domain;

/**
 * 债权聚合根（A-9，R04 §2/§3.4）。状态变更只走本类方法（R00 §5：禁止直接 UPDATE ... SET status）。
 *
 * <p>不变式 I-1：一份合同只允许一个成功代付（uk_disb_loan + 本类守卫双兜底）。
 * 金额一律整数日元（long，R00 §5），禁止 double/float。</p>
 */
public class Loan {

    private final Long loanId;
    private final long contractId;
    private LoanStatus status;
    private final long principal;
    private final long feeAmount;
    private final long balance;
    private java.time.Instant disbursedAt;
    private final int version;

    private Loan(Long loanId, long contractId, LoanStatus status, long principal, long feeAmount,
                 long balance, java.time.Instant disbursedAt, int version) {
        if (principal < 0 || feeAmount < 0) {
            throw new IllegalArgumentException("金额必须为非负整数日元");
        }
        this.loanId = loanId;
        this.contractId = contractId;
        this.status = status;
        this.principal = principal;
        this.feeAmount = feeAmount;
        this.balance = balance;
        this.disbursedAt = disbursedAt;
        this.version = version;
    }

    /** 建立债权（合同生效后的账务前半起点，W5）。初始余额 = 本金 + 费用（INV-2 口径）。 */
    public static Loan create(long contractId, long principal, long feeAmount) {
        return new Loan(null, contractId, LoanStatus.PENDING_DISBURSEMENT,
                principal, feeAmount, principal + feeAmount, null, 0);
    }

    /** repository 复原。 */
    public static Loan restore(Long loanId, long contractId, LoanStatus status, long principal,
                               long feeAmount, long balance, java.time.Instant disbursedAt, int version) {
        return new Loan(loanId, contractId, status, principal, feeAmount, balance, disbursedAt, version);
    }

    /** 代付守卫（INV-1）：只有未代付或可重放的失败态才允许发起代付指令。 */
    public void assertDisbursementAllowed() {
        if (status == LoanStatus.PENDING_DISBURSEMENT || status == LoanStatus.DISBURSE_FAILED) {
            return;
        }
        if (status == LoanStatus.DISBURSING || status == LoanStatus.ACTIVE) {
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

    public long balance() {
        return balance;
    }

    public java.time.Instant disbursedAt() {
        return disbursedAt;
    }

    public int version() {
        return version;
    }
}
