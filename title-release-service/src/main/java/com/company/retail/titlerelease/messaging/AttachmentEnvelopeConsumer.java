package com.company.retail.titlerelease.messaging;

import com.company.platform.storage.DirectUploadEndpoint;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeConsumer;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.common.messaging.MqEnvelopeException;
import com.company.retail.titlerelease.application.AttachmentUploadService;
import com.company.retail.titlerelease.persistence.AttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Objects;

/**
 * 附件密文信封消费端（ADR-RF-014：控制器把密文扔进 MQ，落盘在消费端异步做）。
 *
 * <p><b>职责</b>：解密后由基础设施层交给本类（消费端不接触密钥）→ 校验载荷 →
 * 落盘 → 登记「待登记」占位行。落盘与登记走 {@link AttachmentUploadService}，
 * 与同步直传路径共用同一实现。</p>
 *
 * <p><b>失败语义</b>：载荷非法一律抛 {@link MqEnvelopeException}（不吞异常），
 * 由消费端机制计数重试、达上限置 DEAD（R09 §5.4 铁律 2）。</p>
 *
 * <p><b>留痕</b>：日志只记 envelopeId / objectKey / 字节数，绝不记文件内容与载荷明文（B-1）。</p>
 */
@Component
public class AttachmentEnvelopeConsumer implements EnvelopeConsumer {

    /** 消费者名（幂等键的一部分：envelopeId + consumer）。 */
    public static final String CONSUMER_NAME = "title-release-attachment";

    private static final Logger log = LoggerFactory.getLogger(AttachmentEnvelopeConsumer.class);

    private final AttachmentUploadService uploadService;

    public AttachmentEnvelopeConsumer(ObjectProvider<DirectUploadEndpoint> endpointProvider,
                                      AttachmentRepository repository) {
        this.uploadService = new AttachmentUploadService(endpointProvider, repository);
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public String topic() {
        return EnvelopeTopics.ATTACHMENT;
    }

    @Override
    public void consume(CipherEnvelope envelope, String payloadJson) {
        AttachmentEnvelopePayload payload = AttachmentEnvelopePayload.fromJson(payloadJson);
        if (!Objects.equals(payload.objectKey(), envelope.bizRef())) {
            // 载荷对象键与信封业务键不一致 = 串改/错投，拒绝消费
            throw new MqEnvelopeException("附件载荷对象键与信封业务键不一致，拒绝消费");
        }
        byte[] bytes = decode(payload.contentBase64());
        if (bytes.length != payload.contentLength()) {
            throw new MqEnvelopeException("附件载荷长度与声明不一致，拒绝消费");
        }
        String uploadedBy = blankTo(payload.uploadedBy(), "SYSTEM");
        String traceId = blankTo(payload.traceId(), envelope.traceId());
        uploadService.storeAndRegisterPending(payload.objectKey(), bytes, uploadedBy, traceId);
        log.info("附件上传已落盘并登记待登记行 envelopeId={} objectKey={} size={}",
                envelope.envelopeId(), payload.objectKey(), bytes.length);
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64 == null ? "" : base64);
        } catch (IllegalArgumentException e) {
            throw new MqEnvelopeException("附件载荷内容不是合法 base64", e);
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
