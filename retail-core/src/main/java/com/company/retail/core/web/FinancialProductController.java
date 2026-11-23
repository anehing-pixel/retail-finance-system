package com.company.retail.core.web;

import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.security.SubjectType;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.core.application.ProductCatalogService;
import com.company.retail.core.application.ProductCatalogService.ProductCommand;
import com.company.retail.core.domain.ProductProfile;
import com.company.retail.domain.model.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 产品档位目录端点（写操作权限点 CONFIG_MAINTAIN；读接口不设权限点，
 * 但员工侧主体由 {@code @RequireSubject(EMPLOYEE)} 约束）。
 */
@RestController
@RequireSubject(SubjectType.EMPLOYEE)
public class FinancialProductController {

    private final ProductCatalogService service;

    public FinancialProductController(ProductCatalogService service) {
        this.service = service;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    /** 新建档位（首个版本）。 */
    @RequirePermission(Permission.CONFIG_MAINTAIN)
    @PostMapping("/api/financial-products")
    public ApiResponse<ProductProfile> create(@RequestBody ProductCommand cmd) {
        return ApiResponse.ok(service.create(cmd, traceId()), traceId());
    }

    /** 改版：旧版本封口停用 + 新版本插入（同一事务，提交后失效缓存）。 */
    @RequirePermission(Permission.CONFIG_MAINTAIN)
    @PutMapping("/api/financial-products/{code}")
    public ApiResponse<ProductProfile> revise(@PathVariable("code") String code,
                                              @RequestBody ProductCommand cmd) {
        return ApiResponse.ok(service.revise(code, cmd, traceId()), traceId());
    }

    /** 当前生效档位（走两级缓存）。 */
    @GetMapping("/api/financial-products/{code}")
    public ApiResponse<ProductProfile> current(@PathVariable("code") String code) {
        return ApiResponse.ok(service.findEffective(code), traceId());
    }

    /** 同码全部版本（变更审计视图）。 */
    @GetMapping("/api/financial-products/{code}/versions")
    public ApiResponse<List<ProductProfile>> versions(@PathVariable("code") String code) {
        return ApiResponse.ok(service.versions(code), traceId());
    }
}
