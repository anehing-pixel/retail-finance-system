package com.company.retail.bureau.web;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.bureau.application.BureauMessageLogRepository;
import com.company.retail.bureau.application.CreditInquiryAppService;
import com.company.retail.bureau.application.CreditInquiryRepository;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.domain.model.Permission;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 征信查询端点（R09 §3：POST /api/credit-inquiries，须 Consent，INV-5）。
 */
@RestController
@RequestMapping("/api/credit-inquiries")
public class CreditInquiryController {

    private final CreditInquiryAppService appService;
    private final CreditInquiryRepository inquiryRepository;
    private final BureauMessageLogRepository messageLogRepository;

    public CreditInquiryController(CreditInquiryAppService appService,
                                   CreditInquiryRepository inquiryRepository,
                                   BureauMessageLogRepository messageLogRepository) {
        this.appService = appService;
        this.inquiryRepository = inquiryRepository;
        this.messageLogRepository = messageLogRepository;
    }

    /** 发起征信查询（G4：同意门禁 fail-closed → ACL 挡板 → 留痕 + 事件）。 */
    @PostMapping
    @RequirePermission(Permission.CREDIT_INQUIRY_EXEC)
    public ApiResponse<Map<String, Object>> create(@RequestBody JsonNode body) {
        long caseId = body.path("caseId").asLong(0);
        long consentId = body.path("consentId").asLong(0);
        if (caseId <= 0 || consentId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "CASE_AND_CONSENT_REQUIRED"));
        }
        var row = appService.execute(caseId, consentId,
                body.path("bureau").asText(null),
                body.path("purpose").asText(null),
                traceId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inquiryId", row.inquiryId());
        data.put("caseId", row.caseId());
        data.put("bureau", row.bureau());
        data.put("purpose", row.purpose());
        data.put("status", row.status());
        return ApiResponse.ok(data, traceId());
    }

    /** 查询留痕（读，不设权限点；报文原文不在此暴露，走留痕表排障通道）。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable long id) {
        var row = inquiryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        Map.of("reason", "INQUIRY_NOT_FOUND", "inquiryId", String.valueOf(id))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inquiryId", row.inquiryId());
        data.put("caseId", row.caseId());
        data.put("bureau", row.bureau());
        data.put("purpose", row.purpose());
        data.put("status", row.status());
        data.put("messageLogCount", messageLogRepository.countByBizRef(String.valueOf(id)));
        return ApiResponse.ok(data, traceId());
    }

    private static String traceId() {
        RequestAttributes attrs = RequestContextHolder.currentRequestAttributes();
        return RfSecurityConfig.traceId(attrs);
    }
}
