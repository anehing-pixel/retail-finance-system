package com.company.retail.contract.domain;

/**
 * 交易形态（R00 §3.4，C-01 用户签：双形态并行）。
 *
 * <p>DB 存 {@code VARCHAR}（{@code core.contracts.transaction_scheme}），转换放 repository 层。</p>
 */
public enum TransactionScheme {

    /** 个品分期（个别信用购买斡旋）——主形态，《分期付款销售法》。 */
    INSTALLMENT_CREDIT,

    /** 汽车贷款（直接贷款）——备选形态，《贷金业法》。 */
    MONEY_LENDING
}
