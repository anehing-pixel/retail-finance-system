package com.company.retail.titlerelease.application.statemachine;

import com.company.retail.statemachine.StateMachineRunner;
import com.company.retail.statemachine.TransitionOutcome;
import com.company.retail.titlerelease.domain.ReceiptAuthority;
import com.company.retail.titlerelease.domain.ReleaseOrder;
import com.company.retail.titlerelease.domain.ReleaseOrderStatus;
import com.company.retail.titlerelease.domain.TitleRetention;
import com.company.retail.titlerelease.domain.TitleRetentionStatus;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解除链路状态迁移入口（用例层唯一通道）。
 *
 * <p>解除订单与所有权保留分两台机器：订单推进与保留推进互不代劳，
 * 「出具文件」永远碰不到保留状态（两态分离 ADR-RF-006）。</p>
 */
@Service
public class ReleaseTransitionService {

    private final StateMachineFactory<ReleaseOrderStatus, ReleaseOrderEvent> orderFactory;
    private final StateMachineFactory<TitleRetentionStatus, TitleRetentionEvent> retentionFactory;

    public ReleaseTransitionService(
            StateMachineFactory<ReleaseOrderStatus, ReleaseOrderEvent> orderFactory,
            StateMachineFactory<TitleRetentionStatus, TitleRetentionEvent> retentionFactory) {
        this.orderFactory = orderFactory;
        this.retentionFactory = retentionFactory;
    }

    // ---------- 解除订单 ----------

    /** 第 3 步：备齐解除文件四件套（CREATED → DOCUMENTS_PREPARED）。 */
    public void prepareDocuments(ReleaseOrder order) {
        fireOrder(order, ReleaseOrderEvent.PREPARE_DOCUMENTS, Map.of());
    }

    /** 第 4 步：出具并交付解除文件（→ DOCUMENTS_ISSUED；<b>不改保留状态</b>）。 */
    public void issueDocuments(ReleaseOrder order) {
        fireOrder(order, ReleaseOrderEvent.ISSUE_DOCUMENTS, Map.of());
    }

    /** 第 5 步：外部提交登记申请（→ EXTERNAL_SUBMITTED；证据链⑥）。 */
    public void submitExternal(ReleaseOrder order, String submissionRef) {
        fireOrder(order, ReleaseOrderEvent.SUBMIT_EXTERNAL,
                headers(ReleaseOrderStateMachineConfigurer.HEADER_SUBMISSION_REF, submissionRef));
    }

    /** 我方见证代录（→ MANUAL_RECORDED；中间态，绝不等于解押完成）。 */
    public void recordManualWitness(ReleaseOrder order) {
        fireOrder(order, ReleaseOrderEvent.RECORD_MANUAL_WITNESS, Map.of());
    }

    /** 外部回执确认（→ REGISTRATION_CONFIRMED；唯一可闭环的权威入口）。 */
    public void confirmRegistration(ReleaseOrder order, ReceiptAuthority authority) {
        fireOrder(order, ReleaseOrderEvent.CONFIRM_REGISTRATION,
                headers(ReleaseOrderStateMachineConfigurer.HEADER_AUTHORITY, authority));
    }

    /** 第 8 步：关闭解除订单（→ CLOSED，终态）。 */
    public void close(ReleaseOrder order) {
        fireOrder(order, ReleaseOrderEvent.CLOSE, Map.of());
    }

    /** 拒绝解除（→ REJECTED，终态）。 */
    public void reject(ReleaseOrder order) {
        fireOrder(order, ReleaseOrderEvent.REJECT, Map.of());
    }

    // ---------- 所有权保留 ----------

    /** 第 7 步：外部回执到位，改写保留为已解押（REGISTERED → RELEASE_REGISTERED）。 */
    public void markReleaseRegistered(TitleRetention retention, ReleaseOrderStatus orderStatus,
                                      ReceiptAuthority authority) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(TitleRetentionStateMachineConfigurer.HEADER_ORDER_STATUS, orderStatus);
        headers.put(TitleRetentionStateMachineConfigurer.HEADER_AUTHORITY, authority);
        fireRetention(retention, TitleRetentionEvent.MARK_RELEASE_REGISTERED, headers);
    }

    /** 第 8 步：关闭保留（→ CLOSED，终态）。 */
    public void close(TitleRetention retention) {
        fireRetention(retention, TitleRetentionEvent.CLOSE, Map.of());
    }

    // ---------- 内部 ----------

    private void fireOrder(ReleaseOrder order, ReleaseOrderEvent event, Map<String, Object> headers) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put(ReleaseOrderStateMachineConfigurer.HEADER_ORDER, order);
        all.putAll(headers);
        TransitionOutcome<ReleaseOrderStatus> outcome =
                StateMachineRunner.fire(orderFactory, order.status(), event, all);
        if (!outcome.accepted()) {
            // 引擎拒绝 ⇒ 领域不允许该迁移：交回领域方法复现既有异常语义（错误码与消息一字不改）
            ReleaseOrderStateMachineConfigurer.applyOrder(order, event, all);
            throw new IllegalStateException("解除订单状态机拒绝迁移：" + order.status() + " → " + event);
        }
    }

    private void fireRetention(TitleRetention retention, TitleRetentionEvent event,
                               Map<String, Object> headers) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put(TitleRetentionStateMachineConfigurer.HEADER_RETENTION, retention);
        all.putAll(headers);
        TransitionOutcome<TitleRetentionStatus> outcome =
                StateMachineRunner.fire(retentionFactory, retention.status(), event, all);
        if (!outcome.accepted()) {
            // 引擎拒绝 ⇒ 领域不允许该迁移：交回领域方法复现既有异常语义（错误码与消息一字不改）
            TitleRetentionStateMachineConfigurer.applyRetention(retention, event, all);
            throw new IllegalStateException("保留状态机拒绝迁移：" + retention.status() + " → " + event);
        }
    }

    private static Map<String, Object> headers(String key, Object value) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(key, value);
        return headers;
    }
}
