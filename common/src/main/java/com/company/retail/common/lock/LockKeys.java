package com.company.retail.common.lock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 锁键命名（{@code R00 §7} 命名定源；ADR-RF-013）。
 *
 * <p>格式：{@code retail:lock:{用途}:{业务标识摘要}}。</p>
 *
 * <p><b>业务标识取 SHA-256 前 16 位十六进制</b>：锁键不落明文客户号 / VIN / 回执号——
 * Redis 键会出现在监控与慢日志里，按 {@code R08} 个人信息隔离口径不做明文落键。</p>
 *
 * <p>用途分域，避免嵌套调用自死锁：DMZ 入站转发（{@code dmz-callback}）与
 * title-release 回执登记（{@code registry-receipt}）是两个进程两把锁，
 * 前者持锁期间会调用后者，故二者<b>不得共用同一把锁</b>。</p>
 */
public final class LockKeys {

    /** 锁键命名空间。 */
    public static final String NAMESPACE = "retail:lock";

    /** 用途：进件去重（同客户 + 同一車 + 窗口期）。 */
    public static final String SCOPE_APPLICATION_DEDUP = "app-dedup";

    /** 用途：DMZ 入站回调串行化（外部方换 nonce 重推同一业务回执）。 */
    public static final String SCOPE_DMZ_CALLBACK = "dmz-callback";

    /** 用途：登记回执登记 + 状态流转串行化。 */
    public static final String SCOPE_REGISTRY_RECEIPT = "registry-receipt";

    private LockKeys() {
    }

    /** 进件去重锁（同客户同 VIN 串行化，窗口期判定在锁内重查）。 */
    public static String applicationDedup(long customerId, String vin) {
        return key(SCOPE_APPLICATION_DEDUP, customerId + ":" + nullSafe(vin));
    }

    /** DMZ 入站回调锁。 */
    public static String dmzCallback(long releaseOrderId, String receiptRef) {
        return key(SCOPE_DMZ_CALLBACK, releaseOrderId + ":" + nullSafe(receiptRef));
    }

    /** 登记回执登记锁。 */
    public static String registryReceipt(long releaseOrderId, String receiptRef) {
        return key(SCOPE_REGISTRY_RECEIPT, releaseOrderId + ":" + nullSafe(receiptRef));
    }

    /** 组装锁键（用途 + 业务标识摘要）。 */
    public static String key(String scope, String businessDiscriminator) {
        return NAMESPACE + ":" + scope + ":" + digest(businessDiscriminator);
    }

    private static String digest(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用，无法生成锁键", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
