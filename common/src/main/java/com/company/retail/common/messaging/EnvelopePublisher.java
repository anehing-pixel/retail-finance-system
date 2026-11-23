package com.company.retail.common.messaging;

/**
 * 密文信封发布端口（生产方出口）。
 *
 * <p><b>启用语义</b>：{@link #enabled()} 为 false 时（默认，{@code retail.mq.enabled=false}），
 * 服务走**同步路径**（业务逻辑就地执行）；为 true 时，控制器/用例层把密文信封投进 MQ，
 * 由消费者异步执行——这就是削峰点。</p>
 *
 * <p>实现见 infrastructure 的 {@code RocketMqEnvelopePublisher}；
 * 未启用时的实现见 {@link NoopEnvelopePublisher}。</p>
 */
public interface EnvelopePublisher {

    /** MQ 通道是否启用。false = 同步路径，true = 入队异步路径。 */
    boolean enabled();

    /**
     * 投递密文信封（同步发送；失败抛异常，由调用方决定回滚还是降级）。
     *
     * @param topic    主题（{@link EnvelopeTopics} 常量）
     * @param envelope 密文信封
     */
    void publish(String topic, CipherEnvelope envelope);
}
