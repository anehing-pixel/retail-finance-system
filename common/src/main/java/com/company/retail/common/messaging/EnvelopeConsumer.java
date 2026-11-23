package com.company.retail.common.messaging;

/**
 * 密文信封消费端口（消费方入口）。
 *
 * <p>实现方拿到的是**已解密的业务载荷 JSON**，不接触密钥；
 * 解密与幂等由 infrastructure 侧的监听器统一承担（机制层不做业务判断）。</p>
 *
 * <p>幂等键：{@code envelopeId} + {@code name()}。</p>
 */
public interface EnvelopeConsumer {

    /** 消费者名（幂等键的一部分，与直投 outbox 消费者命名空间隔离）。 */
    String name();

    /** 订阅主题（{@link EnvelopeTopics} 常量）。 */
    String topic();

    /**
     * 处理一条已解密的业务载荷。
     *
     * @param envelope    原始信封（含 envelopeId、bizType、bizRef、traceId）
     * @param payloadJson 已解密的业务载荷 JSON 文本
     */
    void consume(CipherEnvelope envelope, String payloadJson);
}
