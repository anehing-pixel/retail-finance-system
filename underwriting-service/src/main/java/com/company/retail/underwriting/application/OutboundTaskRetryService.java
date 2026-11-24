package com.company.retail.underwriting.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.persistence.RowValues;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.underwriting.mapper.OutboundTaskMapper;

import java.util.Map;

/**
 * 出站任务人工重触发（P0-7）：DEAD → PENDING；其余状态按幂等/冲突处理。
 */
public class OutboundTaskRetryService {

    private final OutboundTaskMapper mapper;

    public OutboundTaskRetryService(OutboundTaskMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @return 重触发后的状态（PENDING / RETRY，后者为幂等返回当前状态）
     */
    public String retry(long taskId) {
        Map<String, Object> row = mapper.findById(taskId);
        if (row == null) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    Map.of("reason", "TASK_NOT_FOUND", "taskId", taskId));
        }
        String status = RowValues.asString(row, "status");
        if ("DEAD".equals(status)) {
            int updated = mapper.resetToPending(taskId);
            if (updated == 0) {
                throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                        Map.of("reason", "TASK_STATE_CHANGED", "taskId", taskId));
            }
            return "PENDING";
        }
        if ("PENDING".equals(status) || "RETRY".equals(status)) {
            return status;
        }
        throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                Map.of("reason", "TASK_NOT_RETRYABLE", "status", status == null ? "" : status));
    }
}
