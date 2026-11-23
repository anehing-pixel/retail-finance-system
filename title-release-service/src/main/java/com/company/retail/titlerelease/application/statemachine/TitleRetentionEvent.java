package com.company.retail.titlerelease.application.statemachine;

/**
 * 所有权保留状态机事件（引擎触发词汇；进程内，不落库、不出网）。
 *
 * <p><b>只有两个事件</b>：改写为「已解押」与关闭。
 * 「出具解除文件」<b>不在</b>这里——文件出具不改保留状态（两态分离，ADR-RF-006）。</p>
 */
public enum TitleRetentionEvent {

    /** 第 7 步：外部回执到位，改写为已解押（REGISTERED → RELEASE_REGISTERED；INV-3 唯一入口）。 */
    MARK_RELEASE_REGISTERED,
    /** 第 8 步：证据链闭合，关闭保留（→ CLOSED，终态）。 */
    CLOSE
}
