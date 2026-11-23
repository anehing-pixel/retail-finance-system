package com.company.retail.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * MQ 通道配置（{@code retail.mq.*}，ADR-RF-014）。
 *
 * <p><b>默认关闭</b>：{@code enabled=false} 时全部服务走同步路径，
 * 既有冒烟 / E2E / 单测的时序与断言不受影响（零回归）；
 * 投产时由环境变量打开（{@code RETAIL_MQ_ENABLED=true}）。</p>
 *
 * <p><b>数据密钥</b>：{@code data-key} 为空时回落到 {@code retail.pii.data-key}——
 * 密文信封承载的就是 PII，不新增密钥面（R06 §5 密钥分离原则）。</p>
 */
@ConfigurationProperties(prefix = "retail.mq")
public class MqProperties {

    /** MQ 通道总开关（默认关）。 */
    private boolean enabled = false;
    /** 密文信封数据密钥（为空则回落 retail.pii.data-key）。 */
    private String dataKey = "";
    /** 密钥版本（写入信封，轮换用）。 */
    private String keyVersion = "v1";
    /** nameserver 地址（形如 127.0.0.1:9876）。 */
    private String nameServer = "";
    /** 生产者组。 */
    private String producerGroup = "rf-producer";
    /** 消费者组（按服务区分）。 */
    private String consumerGroup = "rf-consumer";
    /** 消费线程数（削峰：消费侧限速的关键旋钮）。RocketMQ 客户端要求 max ≥ min（min 默认 20）。 */
    private int consumeThreadMax = 20;
    /** 单次拉取条数。 */
    private int pullBatchSize = 16;
    /** 发送超时（毫秒）。 */
    private int sendTimeoutMs = 3000;

    /** 通用中继开关（仅 retail-core 打开：outbox → MQ）。 */
    private boolean relayEnabled = false;
    /**
     * 本服务是否由 MQ 中继接管事件消费（直投让位）。
     *
     * <p>打开后：本服务的 {@code EventConsumer} 改由 RF_EVENT_RELAY 主题驱动，
     * 直投（outbox 轮询）默认让位；{@link #directConsumers} 里点名的消费者仍走直投
     * （秒级刚性事件，如同意撤回，走 MQ 会有秒级延迟）。</p>
     */
    private boolean relayConsumerEnabled = false;
    /** 中继轮询间隔（毫秒）。 */
    private long relayIntervalMs = 1000;
    /** 中继单批上限。 */
    private int relayBatchLimit = 100;

    /** 仍走直投（outbox 轮询）不由 MQ 接管的消费者名单（逗号分隔）。 */
    private List<String> directConsumers = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDataKey() {
        return dataKey;
    }

    public void setDataKey(String dataKey) {
        this.dataKey = dataKey;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(String keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public int getConsumeThreadMax() {
        return consumeThreadMax;
    }

    public void setConsumeThreadMax(int consumeThreadMax) {
        this.consumeThreadMax = consumeThreadMax;
    }

    public int getPullBatchSize() {
        return pullBatchSize;
    }

    public void setPullBatchSize(int pullBatchSize) {
        this.pullBatchSize = pullBatchSize;
    }

    public int getSendTimeoutMs() {
        return sendTimeoutMs;
    }

    public void setSendTimeoutMs(int sendTimeoutMs) {
        this.sendTimeoutMs = sendTimeoutMs;
    }

    public boolean isRelayEnabled() {
        return relayEnabled;
    }

    public void setRelayEnabled(boolean relayEnabled) {
        this.relayEnabled = relayEnabled;
    }

    public boolean isRelayConsumerEnabled() {
        return relayConsumerEnabled;
    }

    public void setRelayConsumerEnabled(boolean relayConsumerEnabled) {
        this.relayConsumerEnabled = relayConsumerEnabled;
    }

    public long getRelayIntervalMs() {
        return relayIntervalMs;
    }

    public void setRelayIntervalMs(long relayIntervalMs) {
        this.relayIntervalMs = relayIntervalMs;
    }

    public int getRelayBatchLimit() {
        return relayBatchLimit;
    }

    public void setRelayBatchLimit(int relayBatchLimit) {
        this.relayBatchLimit = relayBatchLimit;
    }

    public List<String> getDirectConsumers() {
        return directConsumers;
    }

    public void setDirectConsumers(List<String> directConsumers) {
        this.directConsumers = directConsumers == null ? new ArrayList<>() : directConsumers;
    }
}
