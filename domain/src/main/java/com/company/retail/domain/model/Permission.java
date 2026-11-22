package com.company.retail.domain.model;

import java.util.Set;

/**
 * 权限点常量（语义层，唯一来源 R00 §3.7 / R08）。
 *
 * <p>格式 {@code <域>_<动作>}，只覆盖写操作；读接口不设权限点，
 * 但个人信息读受 {@code PII_VIEW_FULL} 单独管控。</p>
 *
 * <p>为什么用 String 常量而不是枚举：中台鉴权机制层
 * （{@code PlatformJwtAuthInterceptor} / {@code VerifiedToken}）消费<b>字符串</b>权限——
 * 语义层只负责「声明了什么常量」，翻译层不再做枚举↔字符串转换，
 * JWT 的 {@code perms} 声明与本类常量天然同形，不会出现两套表示漂移。</p>
 *
 * <p>客户侧不设权限点（切断点 ③：客户不是内部岗位，{@code RequireSubject(CUSTOMER)}
 * 控制客户端点，自助操作不进 RBAC）。</p>
 */
public final class Permission {

    private Permission() {
    }

    /** 申请受理/补正。 */
    public static final String APPLICATION_REVIEW = "APPLICATION_REVIEW";
    /** 审查决定。 */
    public static final String UNDERWRITING_DECIDE = "UNDERWRITING_DECIDE";
    /** 审查复核（职责分离另一岗）。 */
    public static final String UNDERWRITING_OVERRIDE = "UNDERWRITING_OVERRIDE";
    /** 发起征信查询（须有效同意，INV-5）。 */
    public static final String CREDIT_INQUIRY_EXEC = "CREDIT_INQUIRY_EXEC";
    /** 身份核验处理。 */
    public static final String IDENTITY_VERIFY = "IDENTITY_VERIFY";
    /** 同意维护。 */
    public static final String CONSENT_MAINTAIN = "CONSENT_MAINTAIN";
    /** 合同签订 / 书面文件交付留痕。 */
    public static final String CONTRACT_ISSUE = "CONTRACT_ISSUE";
    /** 执行代付。 */
    public static final String DISBURSEMENT_EXEC = "DISBURSEMENT_EXEC";
    /** 入金核销。 */
    public static final String PAYMENT_ALLOCATE = "PAYMENT_ALLOCATE";
    /** 人工匹配入金。 */
    public static final String PAYMENT_MANUAL_MATCH = "PAYMENT_MANUAL_MATCH";
    /** 结清确认。 */
    public static final String SETTLEMENT_CONFIRM = "SETTLEMENT_CONFIRM";
    /** 所有权保留登记。 */
    public static final String TITLE_REGISTER = "TITLE_REGISTER";
    /** 解除文件出具。 */
    public static final String RELEASE_DOCUMENT_ISSUE = "RELEASE_DOCUMENT_ISSUE";
    /** 解除登记回执确认（仅外部权威可推进）。 */
    public static final String RELEASE_REGISTER_CONFIRM = "RELEASE_REGISTER_CONFIRM";
    /** 催收处置。 */
    public static final String COLLECTION_HANDLE = "COLLECTION_HANDLE";
    /** 征信报送执行。 */
    public static final String BUREAU_REPORT_EXEC = "BUREAU_REPORT_EXEC";
    /** 征信异议处理。 */
    public static final String BUREAU_DISPUTE_HANDLE = "BUREAU_DISPUTE_HANDLE";
    /** 个人信息全量查看（+用途 +留痕）。 */
    public static final String PII_VIEW_FULL = "PII_VIEW_FULL";
    /** 个人信息导出。 */
    public static final String PII_EXPORT = "PII_EXPORT";
    /** 销售店主数据维护。 */
    public static final String DEALER_MAINTAIN = "DEALER_MAINTAIN";
    /** 审查规则维护。 */
    public static final String RULE_MAINTAIN = "RULE_MAINTAIN";
    /** 批处理作业触发。 */
    public static final String JOB_TRIGGER = "JOB_TRIGGER";
    /** 系统配置维护。 */
    public static final String CONFIG_MAINTAIN = "CONFIG_MAINTAIN";

    /** 全量集合：供角色→权限展开与测试断言用；新增权限点必须登记进这里。 */
    public static final Set<String> ALL = Set.of(
            APPLICATION_REVIEW, UNDERWRITING_DECIDE, UNDERWRITING_OVERRIDE, CREDIT_INQUIRY_EXEC,
            IDENTITY_VERIFY, CONSENT_MAINTAIN, CONTRACT_ISSUE, DISBURSEMENT_EXEC,
            PAYMENT_ALLOCATE, PAYMENT_MANUAL_MATCH, SETTLEMENT_CONFIRM, TITLE_REGISTER,
            RELEASE_DOCUMENT_ISSUE, RELEASE_REGISTER_CONFIRM, COLLECTION_HANDLE,
            BUREAU_REPORT_EXEC, BUREAU_DISPUTE_HANDLE, PII_VIEW_FULL, PII_EXPORT,
            DEALER_MAINTAIN, RULE_MAINTAIN, JOB_TRIGGER, CONFIG_MAINTAIN);
}
