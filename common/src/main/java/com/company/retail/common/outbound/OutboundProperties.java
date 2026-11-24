package com.company.retail.common.outbound;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 出站调用参数（{@code retail.outbound.*}；P0-7 / R02 §7.4）。
 *
 * <p>默认值即生产基线：门禁 connect 500ms / read 2s；资金动作绝不重试；
 * DMS 异步任务按 R07 §4.1 的 1m/5m/30m/2h/6h 退避。</p>
 */
@ConfigurationProperties(prefix = "retail.outbound")
public class OutboundProperties {

    /** 总开关；关闭后保留超时，但不再重试/熔断。 */
    private boolean enabled = true;

    /** 熔断总开关；误伤时可在不关重试的情况下单独关闭。 */
    private boolean circuitEnabled = true;

    private OutboundPolicy gate = OutboundPolicy.of(
            Duration.ofMillis(500), Duration.ofSeconds(2), 2, Duration.ofMillis(100),
            5, Duration.ofSeconds(30));

    private OutboundPolicy gateRaw = OutboundPolicy.of(
            Duration.ofMillis(500), Duration.ofSeconds(2), 1, Duration.ZERO,
            5, Duration.ofSeconds(30));

    private OutboundPolicy query = OutboundPolicy.of(
            Duration.ofSeconds(2), Duration.ofSeconds(10), 1, Duration.ofMillis(500),
            5, Duration.ofSeconds(30));

    private OutboundPolicy write = OutboundPolicy.of(
            Duration.ofSeconds(2), Duration.ofSeconds(10), 1, Duration.ZERO,
            5, Duration.ofSeconds(60));

    private OutboundPolicy advisory = OutboundPolicy.of(
            Duration.ofSeconds(2), Duration.ofSeconds(10), 1, Duration.ofMillis(300),
            5, Duration.ofSeconds(30));

    private OutboundPolicy financial = OutboundPolicy.of(
            Duration.ofSeconds(2), Duration.ofSeconds(10), 1, Duration.ZERO,
            3, Duration.ofSeconds(60));

    private OutboundPolicy forward = OutboundPolicy.of(
            Duration.ofSeconds(2), Duration.ofSeconds(10), 1, Duration.ZERO,
            5, Duration.ofSeconds(30));

    private Tasks tasks = new Tasks();

    public OutboundPolicy policyOf(OutboundProfile profile) {
        switch (profile) {
            case GATE:
                return gate;
            case GATE_RAW:
                return gateRaw;
            case QUERY:
                return query;
            case WRITE:
                return write;
            case ADVISORY:
                return advisory;
            case FINANCIAL:
                return financial;
            case FORWARD:
                return forward;
            default:
                throw new IllegalArgumentException("未知出站档位: " + profile);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCircuitEnabled() {
        return circuitEnabled;
    }

    public void setCircuitEnabled(boolean circuitEnabled) {
        this.circuitEnabled = circuitEnabled;
    }

    public OutboundPolicy getGate() {
        return gate;
    }

    public void setGate(OutboundPolicy gate) {
        this.gate = gate;
    }

    public OutboundPolicy getGateRaw() {
        return gateRaw;
    }

    public void setGateRaw(OutboundPolicy gateRaw) {
        this.gateRaw = gateRaw;
    }

    public OutboundPolicy getQuery() {
        return query;
    }

    public void setQuery(OutboundPolicy query) {
        this.query = query;
    }

    public OutboundPolicy getWrite() {
        return write;
    }

    public void setWrite(OutboundPolicy write) {
        this.write = write;
    }

    public OutboundPolicy getAdvisory() {
        return advisory;
    }

    public void setAdvisory(OutboundPolicy advisory) {
        this.advisory = advisory;
    }

    public OutboundPolicy getFinancial() {
        return financial;
    }

    public void setFinancial(OutboundPolicy financial) {
        this.financial = financial;
    }

    public OutboundPolicy getForward() {
        return forward;
    }

    public void setForward(OutboundPolicy forward) {
        this.forward = forward;
    }

    public Tasks getTasks() {
        return tasks;
    }

    public void setTasks(Tasks tasks) {
        this.tasks = tasks;
    }

    /** {@code integration.outbound_tasks} 调度参数（仅 underwriting-service 使用）。 */
    public static class Tasks {

        /** 调度总开关；DMS 挡板模式（URL 为空）下无论开关都不启动。 */
        private boolean enabled = true;

        /** 扫描间隔。 */
        private Duration scanInterval = Duration.ofSeconds(30);

        /** 单次认领上限。 */
        private int batchSize = 10;

        /** 认领租约；进程崩溃后由下轮扫描回收。 */
        private Duration lease = Duration.ofSeconds(120);

        /** R07 §4.1 退避基线：第 1..5 次调度重试的等待时长。 */
        private List<Duration> retryDelays = List.of(
                Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofHours(2), Duration.ofHours(6));

        /** 最大调度重试次数；达到即 DEAD，等待人工重触发。 */
        private int maxRetries = 5;

        /** 第 retryIndex 次（1 起）调度重试前的等待；越界时取最后一个。 */
        public Duration delayFor(int retryIndex) {
            if (retryDelays == null || retryDelays.isEmpty()) {
                return Duration.ofMinutes(1);
            }
            int index = Math.min(Math.max(retryIndex, 1), retryDelays.size()) - 1;
            return retryDelays.get(index);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getScanInterval() {
            return scanInterval;
        }

        public void setScanInterval(Duration scanInterval) {
            this.scanInterval = scanInterval;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration lease) {
            this.lease = lease;
        }

        public List<Duration> getRetryDelays() {
            return retryDelays;
        }

        public void setRetryDelays(List<Duration> retryDelays) {
            this.retryDelays = retryDelays;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }
}
