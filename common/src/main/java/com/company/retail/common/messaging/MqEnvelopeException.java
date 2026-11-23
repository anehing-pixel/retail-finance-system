package com.company.retail.common.messaging;

/**
 * MQ 通道异常（信封加解密、投递、消费的统一异常类型）。
 *
 * <p>与业务异常（{@code BusinessException}）分开：MQ 故障属于技术故障，
 * 不应被翻译成面向 C 端的业务错误码（那会把「队列不可用」说成「业务拒绝」）。</p>
 */
public class MqEnvelopeException extends RuntimeException {

    public MqEnvelopeException(String message) {
        super(message);
    }

    public MqEnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
