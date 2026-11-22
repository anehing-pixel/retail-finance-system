package com.company.retail.contract.domain;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

/**
 * 合同域错误辅助：状态机违规统一映射 {@code RF_VALID_002}（状态不允许），
 * 供 application 层把领域异常翻译成 {@code ApiResponse}。
 */
public final class ContractDomainException {

    private ContractDomainException() {
    }

    /** 状态机不允许的迁移（R04 §3.3）。 */
    public static BusinessException illegalState(String message) {
        return new BusinessException(RFErrorCode.RF_VALID_002, message);
    }

    /** 参数/引用错误。 */
    public static BusinessException invalid(String message) {
        return new BusinessException(RFErrorCode.RF_VALID_001, message);
    }

    /** 幂等/唯一键冲突（uk_contract_app：一个申请一份合同）。 */
    public static BusinessException conflict(String message) {
        return new BusinessException(RFErrorCode.RF_CONFLICT_001, message);
    }
}
