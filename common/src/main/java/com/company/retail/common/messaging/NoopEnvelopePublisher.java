package com.company.retail.common.messaging;

/**
 * MQ 未启用时的发布端实现（默认装配）。
 *
 * <p>{@link #enabled()} 恒为 false——调用方据此走同步路径，不会误把消息丢进空实现。
 * 若在未启用状态下被强行 publish，直接抛错而不是静默吞掉（静默丢消息是最坏的失败形态）。</p>
 */
public final class NoopEnvelopePublisher implements EnvelopePublisher {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void publish(String topic, CipherEnvelope envelope) {
        throw new MqEnvelopeException("MQ 通道未启用（retail.mq.enabled=false），拒绝丢弃密文信封：topic=" + topic);
    }
}
