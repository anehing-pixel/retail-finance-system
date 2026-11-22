package com.company.retail.bureau;

import com.company.retail.common.web.WhoamiSupport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 骨架自证端点（W2）：health 免鉴权，whoami 过 JWT 三道关回显验签结果。
 *
 * <p>「不建只有启动类的空服务」（R02 §3.2）：走网关 + 过拦截器 + 统一响应 + traceId
 * 这条链路可被冒烟真实断言，业务用例自 S2 起按 R05 落入本服务。</p>
 */
@RestController
public class HealthController {

    @GetMapping("/api/credit-inquiries/health")
    public Object health() {
        return WhoamiSupport.health("bureau-service");
    }

    @GetMapping("/api/credit-inquiries/whoami")
    public Object whoami() {
        return WhoamiSupport.whoami("bureau-service");
    }
}
