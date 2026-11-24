package com.company.retail.common.outbound;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 稳定幂等键（P0-7）：同一业务对象在多次重试/重发时得到同一个键。
 *
 * <p>键只含 {@code [A-Za-z0-9_.-]} 与 {@code :}，可直接放请求头；
 * {@link #messageId(String)} 由键派生确定性 UUID，对端可按 messageId 去重。</p>
 */
public final class IdempotencyKeys {

    private static final int MAX_LENGTH = 128;

    private IdempotencyKeys() {
    }

    public static String of(Object... parts) {
        StringBuilder builder = new StringBuilder();
        for (Object part : parts) {
            if (builder.length() > 0) {
                builder.append(':');
            }
            builder.append(sanitize(part == null ? null : String.valueOf(part)));
        }
        if (builder.length() == 0) {
            return "NA";
        }
        return builder.length() <= MAX_LENGTH ? builder.toString() : builder.substring(0, MAX_LENGTH);
    }

    public static UUID messageId(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "NA";
        }
        String cleaned = raw.trim()
                .replaceAll("[^A-Za-z0-9_.-]", "-")
                .replaceAll("-{2,}", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "NA" : cleaned;
    }
}
