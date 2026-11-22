package com.company.retail.bureau.domain;

/**
 * 征信机构（R00 §3.4）。CIC 同时是分期付款销售法与贷金业法下的指定信用信息机构（R01 §2.4）；
 * C-05 裁决：查询双机构（CIC + JICC），报送主 CIC。
 */
public enum CreditBureau {
    CIC,
    JICC,
    ZENGIN
}
