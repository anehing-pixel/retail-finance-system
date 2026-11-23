package com.company.retail.customer.integration;

/**
 * eKYC 供应商端口（P-12，R07 §4.13，形状冻结；W-SIM2 六向防腐层之一）。
 *
 * <p>红线 B-7：出站报文<b>只允许脱敏号/引用</b>，明文 PII 不出域——
 * 违者对端（含模拟器）直接拒绝。双模装配：{@code retail.kyc.provider-url}
 * 空 = 无外部供应商（纯人工 MANUAL 流程，C-07 原语义），非空 = HTTP 适配器。</p>
 */
public interface KycProvider {

    /**
     * 核验受理提交（核验流程 start 时触发；人工复核通道并行保留）。
     *
     * @param verificationId 核验单 ID
     * @param maskedNumber   证件脱敏号（****9999 形态）
     * @param documentType   材料类型引用
     * @return 供应商受理结果（providerRef 留痕进 result_snapshot；null=未配置供应商）
     */
    KycSubmission submit(long verificationId, String maskedNumber, String documentType);

    /** 供应商受理结果。 */
    record KycSubmission(String providerRef, String status, String providerName) {
    }
}
