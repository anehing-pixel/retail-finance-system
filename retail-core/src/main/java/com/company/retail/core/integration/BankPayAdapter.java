package com.company.retail.core.integration;

/**
 * 银行代付网关端口（P-09，R07 §4.10；W-SIM2 六向防腐层之一）。
 *
 * <p>双模装配（沿用 bureau HttpCicAdapter 范式）：{@code retail.core.bank-pay-url}
 * 空 = 内联挡板（原 W6 语义，PAID 同步假成功）；非空 = HTTP 适配器，
 * 按提议协议 v1 信封调外部对端（机构模拟器 / 未来真实银行网关），接口不变。</p>
 */
public interface BankPayAdapter {

    /**
     * 发起代付指令。
     *
     * @param instructionRef  我方指令引用（INS-*，幂等对账键）
     * @param amount          金额（BIGINT 整数日元，协议 v1 口径）
     * @param payeeAccountRef 收款账户引用（脱敏引用，不出域）
     * @return 银行受理结果（paid=true 才允许推进债权状态）
     */
    PayResult pay(String instructionRef, long amount, String payeeAccountRef);

    /** 银行受理结果：paid + 银行引用号 + 原始报文摘要（留痕用，不含敏感信息）。 */
    record PayResult(boolean paid, String bankRef, String rawMessage) {

        public static PayResult paid(String bankRef) {
            return new PayResult(true, bankRef, "");
        }
    }
}
