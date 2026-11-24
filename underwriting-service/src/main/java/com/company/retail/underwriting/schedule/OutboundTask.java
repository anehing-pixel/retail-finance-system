package com.company.retail.underwriting.schedule;

import com.company.retail.common.persistence.RowValues;

import java.util.Map;

/**
 * 出站任务行（P0-7）：认领 SQL 返回的 {@code Map} 收敛成类型化记录。
 */
public record OutboundTask(long taskId, String taskType, String payloadRef,
                           int retryCount, String traceId, int version) {

    public static OutboundTask from(Map<String, Object> row) {
        return new OutboundTask(
                RowValues.asLong(row, "task_id"),
                RowValues.asString(row, "task_type"),
                RowValues.asString(row, "payload_ref"),
                RowValues.asInt(row, "retry_count"),
                RowValues.asString(row, "trace_id"),
                RowValues.asInt(row, "version"));
    }
}
