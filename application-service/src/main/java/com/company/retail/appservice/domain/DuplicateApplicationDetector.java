package com.company.retail.appservice.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 重复申请检测（R04 §5.2 DuplicateApplicationDetector）：同一人 + 同一車 + 窗口期去重。
 * 纯函数：候选记录的筛选（SQL）由仓储做，本类只判定。
 */
public final class DuplicateApplicationDetector {

    private DuplicateApplicationDetector() {
    }

    /**
     * @param priorSubmittedAt 窗口内同客户同 VIN 在途申请的提交时刻（非 null 才进入判定）
     * @param now              本次提交时刻
     * @param window           去重窗口（配置 retail.application.duplicate-window-hours，默认 72h）
     * @return true = 命中重复（RF_APP_001）
     */
    public static boolean isDuplicate(Instant priorSubmittedAt, Instant now, Duration window) {
        if (priorSubmittedAt == null || now == null || window == null || window.isNegative() || window.isZero()) {
            return false;
        }
        return !priorSubmittedAt.isBefore(now.minus(window));
    }
}
