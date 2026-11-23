package com.company.retail.contract.application;

import com.company.retail.contract.integration.HttpDeliveryChannelAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 法定书面文件送达动作（P-14；ADR-RF-014 削峰分叉点）。
 *
 * <p><b>为什么抽成 handler</b>：同步路径（控制器就地送达）与 MQ 路径（密文信封消费端送达）
 * 必须走<b>同一份</b>业务动作——两份实现就是两条口径，迟早漂移（ADR-RF-014 §2 铁律）。</p>
 *
 * <p><b>失败不抛</b>：送达通道受理推送是咨询性副本，权威留痕在 statutory_documents；
 * 通道不可用只记日志，不中断留痕主流程（沿用 {@link HttpDeliveryChannelAdapter} 的原语义）。</p>
 *
 * <p><b>载荷契约</b>：本类同时拥有送达载荷的 JSON 形态（生产端序列化 / 消费端解析），
 * 两端共用一套字段名，杜绝「生产者写 A、消费端读 B」的漂移。投递内容正文只允许出现在
 * 密文信封内部，禁止进日志与异常消息（B-1）。</p>
 */
@Service
public class DeliveryHandler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 送达动作命令（同步入口与 MQ 消费端共用同一份形状）。
     *
     * @param contractId     合同 ID（业务主键，非个人信息）
     * @param docType        书面文件类型
     * @param deliveryMethod 送达方式
     * @param templateVersion 模板版本
     * @param attachmentId   附件引用（可空）
     * @param contentRef     投递内容引用（附件 ID 或模板版本引用）
     * @param contentText    投递内容正文（挡板模式为空；只允许出现在密文信封内部）
     * @param traceId        链路追踪 ID
     */
    public record DeliveryCommand(long contractId, String docType, String deliveryMethod,
                                  String templateVersion, Long attachmentId,
                                  String contentRef, String contentText, String traceId) {

        /** 同步路径构造：内容引用按「附件优先、模板兜底」口径生成，正文留空（挡板不生成正文）。 */
        public static DeliveryCommand of(long contractId, String docType, String deliveryMethod,
                                         String templateVersion, Long attachmentId, String traceId) {
            String ref = attachmentId != null
                    ? "attachment:" + attachmentId
                    : "template:" + (templateVersion == null ? "" : templateVersion);
            return new DeliveryCommand(contractId, docType, deliveryMethod, templateVersion,
                    attachmentId, ref, "", traceId);
        }
    }

    /** 送达通道（W-SIM2 双模：null=未配置，纯 DB 交付留痕 C-08）。 */
    private final HttpDeliveryChannelAdapter adapter;

    public DeliveryHandler(ObjectProvider<HttpDeliveryChannelAdapter> adapterProvider) {
        this.adapter = adapterProvider == null ? null : adapterProvider.getIfAvailable();
    }

    /**
     * 送达动作：把法定书面文件的交付动作推给送达通道受理。
     *
     * <p>失败不抛（沿用 {@link HttpDeliveryChannelAdapter} 原语义）：通道未受理或通道异常
     * 都只记日志，权威留痕在 statutory_documents，不因咨询性副本失败而中断主流程。</p>
     *
     * @param command 送达命令（不得为 null）
     */
    public void deliver(DeliveryCommand command) {
        if (command == null || adapter == null) {
            return;
        }
        try {
            String receiptRef = adapter.notifyDelivered(
                    command.contractId(), command.docType(), command.deliveryMethod(), command.traceId());
            if (receiptRef != null) {
                log.info("送达通道已受理 contractId={} docType={} receiptRef={}",
                        command.contractId(), command.docType(), receiptRef);
            } else {
                log.warn("送达通道推送未受理（留痕主流程不受影响）contractId={} docType={}",
                        command.contractId(), command.docType());
            }
        } catch (RuntimeException e) {
            // 通道异常同样不中断留痕主流程；只记异常类型，不回显载荷（含个人信息）
            log.warn("送达通道推送异常（留痕主流程不受影响）contractId={} docType={} err={}",
                    command.contractId(), command.docType(), e.getClass().getSimpleName());
        }
    }

    /** 送达载荷 → JSON（生产端：只进密文信封内部，broker 上看不到明文）。 */
    public static String payloadJson(DeliveryCommand command) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contractId", command.contractId());
        m.put("docType", nullToEmpty(command.docType()));
        m.put("deliveryMethod", nullToEmpty(command.deliveryMethod()));
        m.put("templateVersion", nullToEmpty(command.templateVersion()));
        m.put("attachmentId", command.attachmentId());
        m.put("contentRef", nullToEmpty(command.contentRef()));
        m.put("contentText", nullToEmpty(command.contentText()));
        m.put("traceId", nullToEmpty(command.traceId()));
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("送达载荷序列化失败", e);
        }
    }

    /**
     * 送达载荷 JSON → 命令（消费端）。
     *
     * <p>非法载荷抛 {@link IllegalArgumentException}：不吞异常，交由 broker 重投
     * （重试上限由消费端机制层控制）。异常消息不含载荷内容。</p>
     */
    public static DeliveryCommand fromPayloadJson(String payloadJson) {
        JsonNode node;
        try {
            node = MAPPER.readTree(payloadJson == null ? "" : payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("送达信封载荷不是合法 JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("送达信封载荷不是 JSON 对象");
        }
        long contractId = node.path("contractId").asLong(0L);
        if (contractId <= 0) {
            throw new IllegalArgumentException("送达信封载荷缺 contractId");
        }
        String docType = node.path("docType").asText("");
        String deliveryMethod = node.path("deliveryMethod").asText("");
        if (docType.isBlank() || deliveryMethod.isBlank()) {
            throw new IllegalArgumentException("送达信封载荷缺 docType/deliveryMethod");
        }
        Long attachmentId = node.hasNonNull("attachmentId") ? node.path("attachmentId").asLong() : null;
        return new DeliveryCommand(contractId, docType, deliveryMethod,
                node.path("templateVersion").asText(""), attachmentId,
                node.path("contentRef").asText(""), node.path("contentText").asText(""),
                node.path("traceId").asText(""));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
