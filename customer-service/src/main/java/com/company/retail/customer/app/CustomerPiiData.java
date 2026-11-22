package com.company.retail.customer.app;

import java.time.LocalDate;

/**
 * 客户 PII 数据（应用层入参/出参载体）：明文只在本层与加密组件之间流动，不落日志不进事件。
 */
public record CustomerPiiData(
        String externalRef,
        String name,
        String nameKana,
        String address,
        LocalDate birthDate,
        String phone,
        String employer,
        String annualIncome) {

    /** 受理必填项守卫（G1 一部分）：姓名、住址、电话必填（RF_VALID_001）。 */
    public void assertRequired() {
        if (name == null || name.isBlank()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                    java.util.Map.of("field", "name"));
        }
        if (address == null || address.isBlank()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                    java.util.Map.of("field", "address"));
        }
        if (phone == null || phone.isBlank()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                    java.util.Map.of("field", "phone"));
        }
    }
}
