package com.company.retail.customer.domain;

import com.company.platform.crypto.PiiMasker;

/**
 * PII 脱敏展示策略（机制在 platform {@link PiiMasker}，口径在此，R06 §5 / R08 §5 PII-1）。
 *
 * <p>默认脱敏规则：姓名只显姓（首字符）、电话末 4 位、地址到市区。
 * 假名姓名、工作单位、出生日期、年收入同样只出脱敏值；全量值仅
 * {@code PII_VIEW_FULL} + 用途 + 留痕后返回。</p>
 */
public final class PiiViewPolicy {

    private PiiViewPolicy() {
    }

    /** 姓名/假名：只显姓（保留首字符，其余打码，R06 §5）。 */
    public static String maskName(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        return plain.charAt(0) + "*".repeat(plain.length() - 1);
    }

    /** 电话：末 4 位可辨（keep=4 的保尾脱头）。 */
    public static String maskPhone(String plain) {
        return PiiMasker.maskTail(plain, 4);
    }

    /** 工作单位：保边脱中，首尾各 2 字符。 */
    public static String maskEmployer(String plain) {
        return PiiMasker.maskMiddle(plain, 2);
    }

    /** 年收入：只露末 4 位（元），其余打码。 */
    public static String maskAnnualIncome(String plain) {
        return PiiMasker.maskTail(plain, 4);
    }

    /**
     * 住址：到「市区（区/市/町/村）」为止可辨，其后打码。
     * 找不到行政区后缀时退化为只显前 4 字符。
     */
    public static String maskAddress(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        int cut = -1;
        String[] wardSuffixes = {"区", "市", "町", "村"};
        for (String suffix : wardSuffixes) {
            int idx = plain.indexOf(suffix);
            if (idx >= 0 && (cut < 0 || idx < cut)) {
                cut = idx + suffix.length();
            }
        }
        if (cut <= 0 || cut >= plain.length()) {
            return PiiMasker.maskMiddle(plain, 2);
        }
        return plain.substring(0, cut) + "*".repeat(plain.length() - cut);
    }

    /** 出生日期：全打码（只保留展示一致性格式）。 */
    public static String maskBirthDate(String plain) {
        return plain == null ? null : "****-**-**";
    }
}
