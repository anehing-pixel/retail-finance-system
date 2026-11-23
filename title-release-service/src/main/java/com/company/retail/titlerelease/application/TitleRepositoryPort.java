package com.company.retail.titlerelease.application;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 保留登记仓储端口（{@code core.title_retentions}；权威写者 BC-8 title-release-service，P-5）。
 *
 * <p>{@code uk_title_loan}：一债权一条保留记录。并发写入有两条路径——
 * 操作员主动登记（{@code POST /api/title-retentions}）与 LOAN_SETTLED 事件兜底建单——
 * 两者可能撞唯一键。<b>契约：冲突不得抛异常</b>：{@link #insert} 用
 * {@code ON CONFLICT (loan_id) DO NOTHING}，冲突时不插入并返回 {@code 0}，
 * 由调用方按「对方已建」幂等处理（见
 * {@link TitleReleaseApplicationService#registerRetention} 与
 * {@link TitleReleaseApplicationService#ensureRetentionForSettledLoan}）。</p>
 *
 * <p>不采用「捕获 DuplicateKeyException 再重读」的写法：PostgreSQL 事务内一旦触发
 * 唯一键错误，整笔事务即被置为 aborted，无法继续读库（HSQLDB/H2 不会，行为不一致）。</p>
 */
public interface TitleRepositoryPort {

    /**
     * 插入保留登记记录。
     *
     * @return 新记录主键；**{@code 0} 表示 uk_title_loan 冲突**（并发被抢先，本次未插入）
     */
    long insert(long loanId, String vin, String status, String registrationMode,
                Timestamp registeredAt, String traceId);

    /** 按债权定位保留登记（一债权至多一条）。 */
    Optional<TitleRow> findByLoanId(long loanId);

    /** 按保留记录主键定位。 */
    Optional<TitleRow> findById(long titleId);

    /** 状态迁移（RELEASE_REGISTERED / CLOSED；乐观锁兜底）。 */
    int updateStatus(long titleId, String status, Timestamp releasedAt, int version);

    /**
     * 登记订正：把挡板占位行（{@code vin = UNKNOWN-<loanId>}）订正为正式登记。
     *
     * <p>并发守卫三重：{@code title_id} + {@code version}（乐观锁）+ {@code vin = 占位 VIN}
     * ——只有第一个订正者能把占位行改写掉，因此「订正 + 补发登记事件」天然只发生一次：
     * 调用方按影响行数判定是否补发事件（见
     * {@link TitleReleaseApplicationService#registerRetention}）。</p>
     *
     * <p><b>不改 status</b>：订正只支持同态订正（C-04 裁决口径 REGISTERED），
     * 状态变更只走聚合状态机方法；订正路径只作用于占位行（{@code AND vin = 占位 VIN}），
     * 真实登记行不可能被改写，故不弱化 INV-3
     *「{@code REGISTERED → RELEASE_REGISTERED} 唯一入口是外部登记回执」。</p>
     *
     * @return 影响行数：{@code 1} = 本调用方完成订正（须补发登记事件）；
     *         {@code 0} = 已被他人订正 / 行不存在 / 版本不符（幂等返回，不补发）
     */
    int correctPlaceholderRegistration(long titleId, String placeholderVin, String vin,
                                   String registrationMode, Timestamp registeredAt, int version);


    /** 保留登记行（读模型）。 */
    record TitleRow(long titleId, long loanId, String vin, String status,
                    String registrationMode, Timestamp registeredAt, Timestamp releasedAt,
                    int version) {
    }
}
