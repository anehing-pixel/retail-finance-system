package com.company.retail.externalapi.security;

/**
 * DMZ 调用留痕存储（{@code integration.call_logs}，append-only，R06 §integration）。
 * result 记 SUCCESS 或失败语义码（RF_PTNR_001 等），不记 PII 明文（R07 §6）。
 */
public interface CallLogStore {

    void record(String partnerCode, String endpoint, int httpStatus, long durationMs,
                String result, String traceId);
}
