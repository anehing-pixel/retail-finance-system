package com.company.retail.underwriting.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.domain.model.Permission;
import com.company.retail.underwriting.application.ConsentGateGuard;
import com.company.retail.underwriting.application.UnderwritingCaseAppService;
import com.company.retail.underwriting.application.UnderwritingCaseRepository;
import com.company.retail.underwriting.application.UnderwritingGateProvider;
import com.company.retail.underwriting.domain.AffordabilityInput;
import com.company.retail.underwriting.domain.DecisionOutcome;
import com.company.retail.underwriting.domain.UnderwritingCase;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审查域端点（R09 §3 审查段）。
 *
 * <p><b>职责分离的权限点区分</b>（R08 §4.2）：提交决定 {@code UNDERWRITING_DECIDE} 与
 * 复核 {@code UNDERWRITING_OVERRIDE} 是两个权限点；复核在应用层再硬校验
 * 复核人 ≠ 决定人（{@code RF_AUTH_003}，不可白名单豁免 RB-7）。</p>
 *
 * <p>不提供「直接改 status」的通用端点（R09 §2）：所有状态变更走聚合方法端点。</p>
 */
@RestController
@RequestMapping("/api/underwriting")
public class UnderwritingCaseController {

    private final UnderwritingCaseAppService appService;
    private final UnderwritingCaseRepository caseRepository;
    private final UnderwritingGateProvider gateProvider;

    public UnderwritingCaseController(UnderwritingCaseAppService appService,
                                      UnderwritingCaseRepository caseRepository,
                                      UnderwritingGateProvider gateProvider) {
        this.appService = appService;
        this.caseRepository = caseRepository;
        this.gateProvider = gateProvider;
    }

    /** 建立审查案件（G3 同意门禁 fail-closed 在应用层）。 */
    @PostMapping("/cases")
    @RequirePermission(Permission.UNDERWRITING_DECIDE)
    public ApiResponse<Map<String, Object>> createCase(@RequestBody JsonNode body, HttpServletRequest request) {
        long applicationId = requireLong(body, "applicationId");
        long consentId = requireLong(body, "consentId");
        UnderwritingCase testCase = appService.createCase(applicationId, consentId,
                body.path("ruleVersion").asText("W4-DEFAULT"), traceId());
        return ApiResponse.ok(view(testCase), traceId());
    }

    /** 发起征信查询状态推进（查询本身由 bureau-service 执行，BC-4）。 */
    @PostMapping("/cases/{id}/start-inquiry")
    @RequirePermission(Permission.CREDIT_INQUIRY_EXEC)
    public ApiResponse<Map<String, Object>> startInquiry(@PathVariable long id) {
        return ApiResponse.ok(view(appService.startInquiry(id)), traceId());
    }

    /** 支付能力评估（C-03 挡板策略点；G5）。 */
    @PostMapping("/cases/{id}/affordability")
    @RequirePermission(Permission.UNDERWRITING_DECIDE)
    public ApiResponse<Map<String, Object>> assessAffordability(@PathVariable long id,
                                                                @RequestBody JsonNode body) {
        AffordabilityInput input = new AffordabilityInput(
                body.path("scheme").asText(null),
                body.path("annualIncomeEncRef").asText(null),
                body.path("existingDebtTotal").asLong(0),
                body.path("thresholdAmount").asLong(0),
                body.path("requestedAmount").asLong(0));
        var output = appService.assessAffordability(id, input, traceId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result", output.result().name());
        data.put("reasonCode", output.reasonCode());
        data.put("policyVersion", output.policyVersion());
        return ApiResponse.ok(data, traceId());
    }

    /** 审查决定（G6）：决定留痕 + 事件；决定人取验签身份（R08 RB-8）。 */
    @PostMapping("/cases/{id}/decision")
    @RequirePermission(Permission.UNDERWRITING_DECIDE)
    public ApiResponse<Map<String, Object>> decide(@PathVariable long id,
                                                   @RequestBody JsonNode body,
                                                   HttpServletRequest request) {
        String decidedBy = requireActor(request);
        DecisionOutcome outcome = parseOutcome(body.path("outcome").asText(null));
        UnderwritingCase testCase = appService.decide(id, decidedBy, outcome,
                body.path("reasonCode").asText(null), traceId());
        return ApiResponse.ok(view(testCase), traceId());
    }

    /** 人工复核 / override：独立权限点 + 复核人 ≠ 决定人（SOD 硬阻断）。 */
    @PostMapping("/cases/{id}/override")
    @RequirePermission(Permission.UNDERWRITING_OVERRIDE)
    public ApiResponse<Map<String, Object>> override(@PathVariable long id,
                                                     @RequestBody JsonNode body,
                                                     HttpServletRequest request) {
        String reviewedBy = requireActor(request);
        DecisionOutcome outcome = parseOutcome(body.path("outcome").asText(null));
        UnderwritingCase testCase = appService.overrideDecision(id, reviewedBy, outcome,
                body.path("reasonCode").asText(null), traceId());
        return ApiResponse.ok(view(testCase), traceId());
    }

    /**
     * 代付前审查门禁（R09 §7.1 provider 侧，供 W5 retail-core）。
     * JSON 形状与冻结契约 {@code FeignContracts.GateResult} 一致：{allowed, reasonCode}。
     * 案件不存在 → allowed=false（fail-closed，绝不放行）。
     */
    @GetMapping("/cases/{id}/disbursement-gate")
    public ApiResponse<UnderwritingGateProvider.GateView> disbursementGate(@PathVariable long id) {
        return ApiResponse.ok(
                gateProvider.check(id).orElse(new UnderwritingGateProvider.GateView(false, "CASE_NOT_FOUND")),
                traceId());
    }

    /** 案件查询（读，不设权限点，R08 §1）。 */
    @GetMapping("/cases/{id}")
    public ApiResponse<Map<String, Object>> getCase(@PathVariable long id) {
        UnderwritingCase testCase = caseRepository.findById(id)
                .orElseThrow(() -> new com.company.platform.exception.BusinessException(
                        RFErrorCode.RF_VALID_001, Map.of("reason", "CASE_NOT_FOUND", "caseId", String.valueOf(id))));
        return ApiResponse.ok(view(testCase), traceId());
    }

    private static Map<String, Object> view(UnderwritingCase testCase) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseId", testCase.getCaseId());
        data.put("applicationId", testCase.getApplicationId());
        data.put("consentId", testCase.getConsentId());
        data.put("status", testCase.getStatus().name());
        data.put("outcome", testCase.getOutcome() == null ? null : testCase.getOutcome().name());
        data.put("decidedBy", testCase.getDecidedBy());
        data.put("reviewedBy", testCase.getReviewedBy());
        data.put("ruleVersion", testCase.getRuleVersion());
        return data;
    }

    private static DecisionOutcome parseOutcome(String text) {
        if (text == null || text.isBlank()) {
            throw new com.company.platform.exception.BusinessException(
                    RFErrorCode.RF_VALID_001, Map.of("field", "outcome"));
        }
        try {
            return DecisionOutcome.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new com.company.platform.exception.BusinessException(
                    RFErrorCode.RF_VALID_001, Map.of("field", "outcome", "value", text));
        }
    }

    private static String requireActor(HttpServletRequest request) {
        Object subject = request.getAttribute("authSubject");
        if (subject == null || String.valueOf(subject).isBlank()) {
            // actor 必须来自验签身份（RB-8：禁止取自请求体/客户端头）
            throw new com.company.platform.exception.BusinessException(RFErrorCode.RF_AUTH_001);
        }
        return String.valueOf(subject);
    }

    private static long requireLong(JsonNode body, String field) {
        if (!body.hasNonNull(field) || body.get(field).asLong() <= 0) {
            throw new com.company.platform.exception.BusinessException(
                    RFErrorCode.RF_VALID_001, Map.of("field", field));
        }
        return body.get(field).asLong();
    }

    private static org.springframework.web.context.request.RequestAttributes attributes() {
        return org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(attributes());
    }
}
