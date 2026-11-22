package com.company.retail.core.domain;

import java.time.LocalDate;

/**
 * 还款计划期次（表 {@code core.schedule_items}，V6）。金额一律整数日元。
 *
 * <p>状态口径：V6 落库默认 {@code OPEN}（R04 的 {@code SCHEDULED} 在 V6 字面为 OPEN），
 * DUE/PAID/OVERDUE 流转归 W6 入金核销波次。</p>
 */
public record ScheduleItem(int periodNo, LocalDate dueDate, long amountDue,
                           long principalDue, long interestDue, String status, long paidAmount) {

    public static final String STATUS_OPEN = "OPEN";
}
