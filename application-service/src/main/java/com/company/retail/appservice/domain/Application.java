package com.company.retail.appservice.domain;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

import java.time.Instant;
import java.util.Map;

/**
 * 申请聚合（A-4，R04 §2）：提交幂等（键同体不同→冲突）、提交后不可改关键项、终态不可再迁移。
 * 纯领域对象，零 Spring（R04 §5）。
 */
public class Application {

    /** 申请车辆项（一台车一条，R06 §4.2 core.application_items）。 */
    public record Item(String vin, String model, long cashPrice, long accessoriesAmount) {
    }

    private Long applicationId;
    private String applicationNo;
    private final long customerId;
    private final Long dealerPartnerId;
    private ApplicationStatus status;
    private final String transactionScheme;
    private final String channel;
    private final Instant submittedAt;
    /** 申请时声明的同意引用（ consent 取得凭证；有效性由 customer-service 门禁数据面校验）。 */
    private final Long consentId;

    private Application(Long applicationId, String applicationNo, long customerId, Long dealerPartnerId,
                        ApplicationStatus status, String transactionScheme, String channel,
                        Instant submittedAt, Long consentId) {
        this.applicationId = applicationId;
        this.applicationNo = applicationNo;
        this.customerId = customerId;
        this.dealerPartnerId = dealerPartnerId;
        this.status = status;
        this.transactionScheme = transactionScheme;
        this.channel = channel;
        this.submittedAt = submittedAt;
        this.consentId = consentId;
    }

    /**
     * 提交（DRAFT→SUBMITTED 的领域入口）：必填项齐（RF_VALID_001）+ 交易形态必填（RF_DMS_003）
     * + 同意已取得（G1：consentId 必填，RF_UW_003）+ 车辆项必填。
     */
    public static Application submit(long customerId, Long dealerPartnerId, String transactionScheme,
                                     String channel, Long consentId, Item vehicle, Instant at) {
        if (customerId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "customerId"));
        }
        if (transactionScheme == null || transactionScheme.isBlank()) {
            // C-01 双形态：每笔申请必填（INSTALLMENT_CREDIT / MONEY_LENDING）
            throw new BusinessException(RFErrorCode.RF_DMS_003);
        }
        if (consentId == null) {
            // G1 闸门：取得同意是申请受理前置（R05 §4 G1）
            throw new BusinessException(RFErrorCode.RF_UW_003);
        }
        if (vehicle == null || vehicle.vin() == null || vehicle.vin().isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "vin"));
        }
        if (vehicle.cashPrice() <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, Map.of("field", "cashPrice"));
        }
        return new Application(null, null, customerId, dealerPartnerId,
                ApplicationStatus.SUBMITTED, transactionScheme,
                channel == null || channel.isBlank() ? "PORTAL" : channel, at, consentId);
    }

    /** 仓储回放入口。 */
    public static Application restore(Long applicationId, String applicationNo, long customerId,
                                      Long dealerPartnerId, ApplicationStatus status,
                                      String transactionScheme, String channel, Instant submittedAt,
                                      Long consentId) {
        return new Application(applicationId, applicationNo, customerId, dealerPartnerId,
                status, transactionScheme, channel, submittedAt, consentId);
    }

    /** 撤回（客户动作，仅 SUBMITTED 可撤，R04 §3.1）。 */
    public void withdraw() {
        status.assertTransitionTo(ApplicationStatus.WITHDRAWN);
        status = ApplicationStatus.WITHDRAWN;
    }

    /** 系统初检：转入补件（文件受理前的 DOCUMENTS_PENDING）。 */
    public void requestDocuments() {
        status.assertTransitionTo(ApplicationStatus.DOCUMENTS_PENDING);
        status = ApplicationStatus.DOCUMENTS_PENDING;
    }

    /**
     * 通用状态推进（唯一合法路径：目标必须是当前状态在 R04 §3.1 表中的直接后继）。
     * 接口不暴露「直接改 status」的端点——本方法只被具体用例方法调用。
     */
    public void transitionTo(ApplicationStatus target) {
        status.assertTransitionTo(target);
        status = target;
    }

    public boolean isTerminal() {
        return ApplicationStatus.TERMINAL.contains(status);
    }

    public Long getApplicationId() {
        return applicationId;
    }

    public void assignIdentity(long applicationId, String applicationNo) {
        if (this.applicationId != null) {
            throw new BusinessException(RFErrorCode.RF_VALID_002, Map.of("reason", "身份已赋值"));
        }
        this.applicationId = applicationId;
        this.applicationNo = applicationNo;
    }

    public long getCustomerId() {
        return customerId;
    }

    public Long getDealerPartnerId() {
        return dealerPartnerId;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public String getTransactionScheme() {
        return transactionScheme;
    }

    public String getChannel() {
        return channel;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Long getConsentId() {
        return consentId;
    }

    public String getApplicationNo() {
        return applicationNo;
    }
}
