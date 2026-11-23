package com.company.retail.contract.messaging;

import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.contract.application.DeliveryHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 送达链路密文信封消费端（ADR-RF-014 §3）。
 *
 * <p>解密后交给 {@link DeliveryHandler}——与同步路径同一份送达动作，本类只负责
 * 「解析载荷 + 转交」，不碰业务语义。载荷非法直接抛（不吞异常），由消费端机制层
 * 计数重试与 DEAD 兜底。</p>
 */
@Component
public class ContractDeliveryEnvelopeConsumer implements EnvelopeConsumer {

    private static final Logger log = LoggerFactory.getLogger(ContractDeliveryEnvelopeConsumer.class);

    private final DeliveryHandler handler;

    public ContractDeliveryEnvelopeConsumer(DeliveryHandler handler) {
        this.handler = handler;
    }

    @Override
    public String name() {
        return "contract-delivery";
    }

    @Override
    public String topic() {
        return EnvelopeTopics.DELIVERY;
    }

    @Override
    public void consume(CipherEnvelope envelope, String payloadJson) {
        DeliveryHandler.DeliveryCommand command = DeliveryHandler.fromPayloadJson(payloadJson);
        handler.deliver(command);
        log.info("送达密文信封已处理 contractId={} docType={} envelopeId={}",
                command.contractId(), command.docType(), envelope.envelopeId());
    }
}
