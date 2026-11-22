package com.company.retail.core.domain;

/**
 * 还款方式（R00 §3.4 {@code RepaymentMethod}）。
 */
public enum RepaymentMethod {

    /** 等额本息（元利均等）。 */
    EQUAL_PAYMENT,

    /** 等额本金（元金均等）。 */
    EQUAL_PRINCIPAL,

    /** 奖金还款（bonus 月加重扣款）。 */
    BONUS_PAYMENT,

    /** 一次性。 */
    LUMP_SUM
}
