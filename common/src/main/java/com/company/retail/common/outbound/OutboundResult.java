package com.company.retail.common.outbound;

/**
 * 出站调用结果（P0-7）：把「HTTP 状态 / 传输失败 / 熔断打开」收敛成一个形状，
 * 适配器据此保持原有返回语义（null / false / PayResult(false) / 异常）。
 */
public final class OutboundResult {

    private final int status;
    private final String body;
    private final String errorKind;
    private final int attempts;
    private final long durationMs;
    private final boolean circuitOpen;

    private OutboundResult(int status, String body, String errorKind, int attempts,
                           long durationMs, boolean circuitOpen) {
        this.status = status;
        this.body = body;
        this.errorKind = errorKind;
        this.attempts = attempts;
        this.durationMs = durationMs;
        this.circuitOpen = circuitOpen;
    }

    public static OutboundResult of(int status, String body, int attempts, long durationMs) {
        return new OutboundResult(status, body, null, attempts, durationMs, false);
    }

    public static OutboundResult error(String errorKind, int attempts, long durationMs) {
        return new OutboundResult(-1, "", errorKind, attempts, durationMs, false);
    }

    public static OutboundResult circuitOpen(long durationMs) {
        return new OutboundResult(-1, "", "CIRCUIT_OPEN", 0, durationMs, true);
    }

    public boolean is2xx() {
        return status >= 200 && status < 300;
    }

    public boolean failed() {
        return status < 0 || status >= 400;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }

    public String getErrorKind() {
        return errorKind;
    }

    public int getAttempts() {
        return attempts;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }
}
