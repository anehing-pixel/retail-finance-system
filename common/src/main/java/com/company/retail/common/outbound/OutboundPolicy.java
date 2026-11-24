package com.company.retail.common.outbound;

import java.time.Duration;

/**
 * 出站策略值（P0-7）：超时、重试上限与熔断阈值。
 *
 * <p>可变 POJO 仅为 Spring Boot 配置绑定；生产代码把它当只读值使用，
 * 不在运行时修改共享实例。</p>
 */
public class OutboundPolicy {

    /** 建立连接超时（JDK HttpClient 的 client 级 connectTimeout）。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 单次请求总超时（JDK HttpClient 的 HttpRequest.timeout，覆盖 connect + read）。 */
    private Duration readTimeout = Duration.ofSeconds(10);

    /** 最大尝试次数（含首次调用）；1 = 不重试。 */
    private int maxAttempts = 1;

    /** 两次尝试之间的固定退避；仅 maxAttempts &gt; 1 时生效。 */
    private Duration retryBackoff = Duration.ofMillis(300);

    /** 连续失败达到该值后熔断打开。 */
    private int failureThreshold = 5;

    /** 熔断打开时长；到期后进入半开，只放一个探针。 */
    private Duration openDuration = Duration.ofSeconds(30);

    public static OutboundPolicy of(Duration connectTimeout, Duration readTimeout, int maxAttempts,
                                    Duration retryBackoff, int failureThreshold, Duration openDuration) {
        OutboundPolicy policy = new OutboundPolicy();
        policy.setConnectTimeout(connectTimeout);
        policy.setReadTimeout(readTimeout);
        policy.setMaxAttempts(maxAttempts);
        policy.setRetryBackoff(retryBackoff);
        policy.setFailureThreshold(failureThreshold);
        policy.setOpenDuration(openDuration);
        return policy;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public Duration getOpenDuration() {
        return openDuration;
    }

    public void setOpenDuration(Duration openDuration) {
        this.openDuration = openDuration;
    }
}
