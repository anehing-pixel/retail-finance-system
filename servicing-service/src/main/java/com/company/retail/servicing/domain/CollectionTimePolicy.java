package com.company.retail.servicing.domain;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 催收时段约束（R04 §7 U-8「时段约束」；催收時段合规默认口径 ⚙️，配置覆盖不改代码）。
 *
 * <p><b>这不是可配置旁路</b>：窗口默认 09:00–20:00（JST），窗口<b>收窄/扩大</b>走配置（P-12），
 * 但「窗口约束本身」不可关闭——不存在 {@code enabled=false} 之类的开关，
 * 窗口外的动作一律拒绝（宁可误拒不可放过，fail-closed）。</p>
 */
public final class CollectionTimePolicy {

    /** 全系统 JST（R00 §5）。 */
    public static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    private final LocalTime windowStart;
    private final LocalTime windowEnd;

    public CollectionTimePolicy(LocalTime windowStart, LocalTime windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException("催收时段窗口非法（start 必须早于 end）");
        }
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
    }

    /** 默认窗口 09:00–20:00 JST。 */
    public static CollectionTimePolicy defaults() {
        return new CollectionTimePolicy(LocalTime.of(9, 0), LocalTime.of(20, 0));
    }

    /** 窗口内（含边界 start，不含边界 end）才允许催收动作。 */
    public boolean allows(OffsetDateTime at) {
        LocalTime t = at.atZoneSameInstant(ZONE_JST).toLocalTime();
        return !t.isBefore(windowStart) && t.isBefore(windowEnd);
    }

    public LocalTime windowStart() {
        return windowStart;
    }

    public LocalTime windowEnd() {
        return windowEnd;
    }
}
