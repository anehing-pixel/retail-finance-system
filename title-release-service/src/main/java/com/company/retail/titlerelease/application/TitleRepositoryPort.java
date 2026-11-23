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

    /** 保留登记行（读模型）。 */
    record TitleRow(long titleId, long loanId, String vin, String status,
                    String registrationMode, Timestamp registeredAt, Timestamp releasedAt,
                    int version) {
    }
}
