package com.company.retail.domain.exception;

import com.company.platform.exception.ErrorCode;

/**
 * 本项目错误码（语义层，唯一来源 R00 §3.5 / R09 §4）。
 *
 * <p>格式 {@code RF_<域>_<3位序号>}，携带 message 与 httpStatus。
 * 机制骨架（{@link com.company.platform.exception.BusinessException} 与全局异常处理）
 * 来自中台 jar；本枚举是全仓唯一错误码字典，新增码必须同步 R00 §3.5。</p>
 */
public enum RFErrorCode implements ErrorCode {

    // ---- 认证/授权 ----
    RF_AUTH_001("未认证 / token 无效", 401),
    RF_AUTH_002("权限不足", 403),
    RF_AUTH_003("职责分离硬阻断（申请人≠复核人等）", 403),

    // ---- 校验/冲突 ----
    RF_VALID_001("参数错误", 400),
    RF_VALID_002("状态不允许该操作", 400),
    RF_CONFLICT_001("幂等键冲突 / 重复请求", 409),

    // ---- 申请 ----
    RF_APP_001("重复申请", 409),
    RF_APP_002("申请状态不允许", 422),

    // ---- 审查授信 ----
    RF_UW_001("征信查询失败（上游不可用）", 502),
    RF_UW_002("超支付能力预估额 / 总量规制", 422),
    RF_UW_003("同意缺失或已撤回", 422),

    // ---- 身份核验 ----
    RF_IDV_001("身份核验材料不齐", 422),
    RF_IDV_002("eKYC 超时", 504),

    // ---- 合同/法定书面文件 ----
    RF_CTR_001("法定书面文件未交付", 422),

    // ---- 债权/代付 ----
    RF_LOAN_001("代付失败", 502),
    RF_LOAN_002("重复代付（INV-1）", 409),

    // ---- 收款核销 ----
    RF_PAY_001("入金不明 / 无法匹配", 422),
    RF_PAY_002("超额入金", 422),

    // ---- 所有权保留/解除 ----
    RF_TITLE_001("非结清不得解除（INV-3）", 422),
    RF_TITLE_002("回执未完 / 权威不足", 422),

    // ---- 征信报送 ----
    RF_BUREAU_001("报送被拒", 502),
    RF_BUREAU_002("异议处理超期", 422),

    // ---- 个人信息 ----
    RF_PII_001("无同意不得访问", 403),
    RF_PII_002("越权个人信息访问已阻断", 403),

    // ---- 外部 partner（DMZ） ----
    RF_PTNR_001("验签失败", 401),
    RF_PTNR_002("nonce 重放", 409),
    RF_PTNR_003("partner 状态 / scope / IP 不允许", 403),

    // ---- DMS 业务语义 ----
    RF_DMS_001("资质不符（预审/审查业务语义）", 422),
    RF_DMS_002("车辆重复申请", 409),
    RF_DMS_003("交易形态字段缺失（双形态下每笔申请必填）", 400),

    // ---- 网关 ----
    RF_GATEWAY_001("请求过于频繁（网关限流）", 429),

    // ---- 兜底 ----
    RF_SYS_001("未预期异常", 500);

    private final String message;
    private final int httpStatus;

    RFErrorCode(String message, int httpStatus) {
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public int getHttpStatus() {
        return httpStatus;
    }
}
