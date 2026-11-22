package com.company.retail.servicing.domain;

import com.company.platform.exception.BusinessException;
import com.company.retail.domain.exception.RFErrorCode;

import java.util.Map;

/**
 * 催收动作类型（R04 A-13 聚合实体 CollectionAction；R08 §7 催收必留痕）。
 *
 * <p>动作必须落在催收案件上（R08：催收必经案件，不允许绕过案件直接操作客户）——
 * 本枚举只描述「案件上的动作」，系统不提供任何绕过案件的客户级催收操作入口。</p>
 */
public enum CollectionActionType {

    /** 分案：指定催收负责人。 */
    ASSIGN,
    /** 联系：电话 / 书面联系留痕。 */
    CONTACT,
    /** 承诺：取得还款承诺（承诺未履约可回 IN_CONTACT）。 */
    PROMISE,
    /** 关案：结果流转，必须登记结果（REPAID / UNRECOVERABLE）。 */
    CLOSE;

    /** 动作 → 案件目标状态。 */
    public CollectionCaseStatus targetStatus() {
        return switch (this) {
            case ASSIGN -> CollectionCaseStatus.ASSIGNED;
            case CONTACT -> CollectionCaseStatus.IN_CONTACT;
            case PROMISE -> CollectionCaseStatus.PROMISED;
            case CLOSE -> CollectionCaseStatus.CLOSED;
        };
    }
}
