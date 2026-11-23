package com.company.retail.customer.app;

import com.company.retail.customer.repo.PiiAccessLogRepository;
import com.company.retail.customer.repo.PiiRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * PII 用例（R06 §5 / R08 §5）：注册、脱敏视图、全量视图；每次访问写 audit.pii_access_logs。
 *
 * <p>脱敏口径 {@code PiiViewPolicy}；全量仅在控制器 {@code PII_VIEW_FULL} 权限 + 用途声明之后到达本类。</p>
 */
@Service
public class CustomerPiiService {

    private final PiiRepository piiRepository;
    private final PiiAccessLogRepository accessLogs;

    public CustomerPiiService(PiiRepository piiRepository, PiiAccessLogRepository accessLogs) {
        this.piiRepository = piiRepository;
        this.accessLogs = accessLogs;
    }

    /** 客户注册（受理岗）：PII 字段级加密落库。 */
    @Transactional
    public long register(CustomerPiiData data, String createdVia, String traceId) {
        data.assertRequired();
        return piiRepository.createCustomer(data, createdVia, traceId);
    }

    /** 脱敏视图（默认出口，R09 §2：所有响应默认脱敏）。 */
    @Transactional
    public java.util.Map<String, String> maskedView(long customerId, String actor, String actorSubject,
                                                    String purpose, String traceId) {
        java.util.Map<String, String> masked = piiRepository.loadDecrypted(customerId)
                .map(PiiRepository::maskedFields)
                .orElseThrow(() -> new com.company.platform.exception.BusinessException(
                        com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                        Map.of("reason", "客户不存在", "customerId", String.valueOf(customerId))));
        accessLogs.record(actor, actorSubject, customerId,
                String.join(",", masked.keySet()), purpose == null ? "MASKED_VIEW" : purpose, traceId);
        return java.util.Collections.unmodifiableMap(masked);
    }

    /** 全量视图：前置条件（PII_VIEW_FULL + 用途）由控制器强制；本方法负责留痕。 */
    @Transactional
    public CustomerPiiData fullView(long customerId, String actor, String actorSubject,
                                    String purpose, String traceId) {
        CustomerPiiData data = piiRepository.loadDecrypted(customerId)
                .orElseThrow(() -> new com.company.platform.exception.BusinessException(
                        com.company.retail.domain.exception.RFErrorCode.RF_VALID_001,
                        Map.of("reason", "客户不存在", "customerId", String.valueOf(customerId))));
        if (purpose == null || purpose.isBlank()) {
            throw new com.company.platform.exception.BusinessException(
                    com.company.retail.domain.exception.RFErrorCode.RF_PII_002,
                    Map.of("reason", "全量查看必须声明用途（R08 §5 PII-6）"));
        }
        accessLogs.record(actor, actorSubject, customerId,
                "name,nameKana,address,birthDate,phone,employer,annualIncome", purpose, traceId);
        return data;
    }
}
