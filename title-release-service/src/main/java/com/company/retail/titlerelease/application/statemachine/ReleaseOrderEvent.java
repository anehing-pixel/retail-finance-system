package com.company.retail.titlerelease.application.statemachine;

/**
 * 解除订单状态机事件（引擎触发词汇；进程内，不落库、不出网）。
 *
 * <p>对应解除八步（R05 §6）：备齐文件 → 出具文件 → 外部提交 → 回执确认 → 关闭。</p>
 */
public enum ReleaseOrderEvent {

    /** 第 3 步：解除文件四件套齐（CREATED → DOCUMENTS_PREPARED）。 */
    PREPARE_DOCUMENTS,
    /** 第 4 步：出具并交付解除文件（→ DOCUMENTS_ISSUED；<b>不改保留状态</b>，两态分离）。 */
    ISSUE_DOCUMENTS,
    /** 第 5 步：外部提交登记申请（→ EXTERNAL_SUBMITTED；证据链⑥）。 */
    SUBMIT_EXTERNAL,
    /** 我方见证代录（→ MANUAL_RECORDED；<b>中间态，绝不等于解押完成</b>，C-11）。 */
    RECORD_MANUAL_WITNESS,
    /** 外部登记回执确认（→ REGISTRATION_CONFIRMED；唯一可闭环的权威入口）。 */
    CONFIRM_REGISTRATION,
    /** 第 8 步：证据链闭合，关闭订单（→ CLOSED，终态）。 */
    CLOSE,
    /** 拒绝（→ REJECTED，终态）。 */
    REJECT
}
