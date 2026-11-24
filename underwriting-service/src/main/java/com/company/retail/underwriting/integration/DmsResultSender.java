package com.company.retail.underwriting.integration;

/**
 * DMS 结果通知端口（P0-7）：消费者与 outbound_tasks 调度器共用同一发送入口，
 * 避免两条路径各自拼报文。
 */
public interface DmsResultSender {

    /**
     * 推送审查决定。
     *
     * @return DMS 受理引用；null = 推送失败（调用方落 outbound_tasks）
     */
    String sendResult(long caseId, long applicationId, String decision, String reasonCode, String traceId);
}
