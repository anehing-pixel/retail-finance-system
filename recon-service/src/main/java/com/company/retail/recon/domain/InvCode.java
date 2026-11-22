package com.company.retail.recon.domain;

/**
 * 对拍视点（INV-1~5，权威定义 R02 §8 / R10 §4 A 组）。
 *
 * <ul>
 *   <li>INV-1：一份合同的代付只发生一次，金额 = 债权本金约定；</li>
 *   <li>INV-2：balance ≡ 代付本金 + 手续费 + 利息 − 已核销本金（<b>精确相等，不容差</b>）；</li>
 *   <li>INV-3：解押必须在债权终态之后；RELEASE_REGISTERED 必须有外部权威回执；</li>
 *   <li>INV-4：任何 outbox 事件载荷不含白名单外键（默认拒绝，INV-4 硬红线）；</li>
 *   <li>INV-5：查询/报送留痕必须锚定存在且未撤销的同意。</li>
 * </ul>
 */
public enum InvCode {

    INV_1,
    INV_2,
    INV_3,
    INV_4,
    INV_5;

    public String code() {
        return "INV-" + ordinalSuffix();
    }

    private String ordinalSuffix() {
        return switch (this) {
            case INV_1 -> "1";
            case INV_2 -> "2";
            case INV_3 -> "3";
            case INV_4 -> "4";
            case INV_5 -> "5";
        };
    }

    /** 差异清单里的 inv_code 存储值（R10 口径）。 */
    public static InvCode fromCode(String code) {
        return switch (code) {
            case "INV-1" -> INV_1;
            case "INV-2" -> INV_2;
            case "INV-3" -> INV_3;
            case "INV-4" -> INV_4;
            case "INV-5" -> INV_5;
            default -> throw new IllegalArgumentException("未知对拍视点：" + code);
        };
    }
}
