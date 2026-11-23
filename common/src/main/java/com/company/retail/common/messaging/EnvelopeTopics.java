package com.company.retail.common.messaging;

/**
 * MQ 主题与消费组常量表（ADR-RF-014 §2）。
 *
 * <p><b>命名口径</b>：主题 {@code RF_ENVELOPE_<链路>}（大写下划线，R00 §6 事件名同风格）；
 * 消费组 {@code rf-<服务名>}（RocketMQ 消费组决定「谁收」，同一消费组内负载均衡）。</p>
 *
 * <p><b>一链路一主题</b>：不混用 tag 做细粒度路由——单节点起步期（不建集群），
 * 主题数少、运维简单；后续按吞吐拆分时再细化（ADR-RF-014 §6 演进触发器）。</p>
 */
public final class EnvelopeTopics {

    private EnvelopeTopics() {
    }

    /** 附件材料链路（title-release）：文件本体与登记元数据。 */
    public static final String ATTACHMENT = "RF_ENVELOPE_ATTACHMENT";
    /** 身份核验材料链路（customer）：核验材料登记。 */
    public static final String IDENTITY_DOCUMENT = "RF_ENVELOPE_IDENTITY";
    /** 征信链路（bureau）：查询与报送报文。 */
    public static final String BUREAU = "RF_ENVELOPE_BUREAU";
    /** 送达链路（contract）：法定书面文件送达内容。 */
    public static final String DELIVERY = "RF_ENVELOPE_DELIVERY";
    /** 通用中继（retail-core）：outbox 业务事件中继（非密文载荷，已过事件白名单）。 */
    public static final String EVENT_RELAY = "RF_EVENT_RELAY";

    /** 消费组：title-release。 */
    public static final String GROUP_TITLE_RELEASE = "rf-title-release";
    /** 消费组：customer。 */
    public static final String GROUP_CUSTOMER = "rf-customer";
    /** 消费组：bureau。 */
    public static final String GROUP_BUREAU = "rf-bureau";
    /** 消费组：contract。 */
    public static final String GROUP_CONTRACT = "rf-contract";
    /** 消费组：underwriting（通用中继）。 */
    public static final String GROUP_UNDERWRITING = "rf-underwriting";
    /** 消费组：servicing（通用中继）。 */
    public static final String GROUP_SERVICING = "rf-servicing";
    /** 消费组：recon（通用中继）。 */
    public static final String GROUP_RECON = "rf-recon";
    /** 消费组：application（通用中继）。 */
    public static final String GROUP_APPLICATION = "rf-application";
}
