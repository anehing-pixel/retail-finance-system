package com.company.retail.core.integration;

/**
 * 银行代付挡板（W6 原语义：instructionRef 派生引用，同步假成功）。
 * bank-pay-url 未配置时的默认行为，链路与既有测试完全一致。
 */
public class StubBankPayAdapter implements BankPayAdapter {

    @Override
    public PayResult pay(String instructionRef, long amount, String payeeAccountRef) {
        return PayResult.paid("STUB-BANK-" + instructionRef);
    }
}
