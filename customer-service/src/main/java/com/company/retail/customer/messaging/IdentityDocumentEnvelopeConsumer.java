package com.company.retail.customer.messaging;

import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.customer.app.IdentityDocumentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 身份核验材料链路密文信封消费端（ADR-RF-014 §3）。
 *
 * <p>解密后交给 {@link IdentityDocumentHandler}——与同步路径同一份登记动作，本类只负责
 * 「解析载荷 + 转交」，不碰业务语义。载荷非法直接抛（不吞异常），由消费端机制层
 * 计数重试与 DEAD 兜底。</p>
 */
@Component
public class IdentityDocumentEnvelopeConsumer implements EnvelopeConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdentityDocumentEnvelopeConsumer.class);

    private final IdentityDocumentHandler handler;

    public IdentityDocumentEnvelopeConsumer(IdentityDocumentHandler handler) {
        this.handler = handler;
    }

    @Override
    public String name() {
        return "customer-identity-document";
    }

    @Override
    public String topic() {
        return EnvelopeTopics.IDENTITY_DOCUMENT;
    }

    @Override
    public void consume(CipherEnvelope envelope, String payloadJson) {
        IdentityDocumentHandler.DocumentCommand command = IdentityDocumentHandler.fromPayloadJson(payloadJson);
        handler.register(command);
        log.info("核验材料密文信封已处理 verificationId={} documentType={} envelopeId={}",
                command.verificationId(), command.documentType(), envelope.envelopeId());
    }
}
