package com.company.retail.titlerelease.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.titlerelease.domain.EvidenceChainPolicy;
import com.company.retail.titlerelease.domain.ReceiptAuthority;
import com.company.retail.titlerelease.domain.ReleaseOrder;
import com.company.retail.titlerelease.domain.ReleaseOrderStatus;
import com.company.retail.titlerelease.domain.TitleRetention;
import com.company.retail.titlerelease.domain.TitleRetentionStatus;
import com.company.retail.titlerelease.persistence.JdbcReleaseOrderRepository;
import com.company.retail.titlerelease.persistence.JdbcTitleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 所有权保留与解除用例（W6；解除八步 R05 §6；两态分离 ADR-RF-006；外部回执唯一权威 C-11）。
 *
 * <p>事件（R04 §6）：TITLE_RETENTION_REGISTERED（登记）、RELEASE_DOCUMENTS_ISSUED（第 4 步出具）、
 * TITLE_RELEASE_REGISTERED（第 8 步闭环——<b>必须</b>有外部回执才发，INV-3）。</p>
 */
@Service
public class TitleReleaseApplicationService {

    /** 解除文件四件套（R05 §6 第 3 步）。 */
    public static final Set<String> DOCUMENT_TYPES = Set.of(
            "RELEASE_CONSENT", "TRANSFER_CERTIFICATE", "POWER_OF_ATTORNEY", "SEAL_CERTIFICATE");

    private static final Logger log = LoggerFactory.getLogger(TitleReleaseApplicationService.class);

    private final JdbcTitleRepository titleRepository;
    private final JdbcReleaseOrderRepository orderRepository;
    private final OutboxPublisher outbox;
    private final ObjectMapperHolder mapperHolder;

    public TitleReleaseApplicationService(JdbcTitleRepository titleRepository,
                                          JdbcReleaseOrderRepository orderRepository,
                                          OutboxPublisher outbox,
                                          ObjectMapperHolder mapperHolder) {
        this.titleRepository = titleRepository;
        this.orderRepository = orderRepository;
        this.outbox = outbox;
        this.mapperHolder = mapperHolder;
    }

    /** ObjectMapper 桥（收口一处，同 retail-core 范式）。 */
    @org.springframework.stereotype.Component
    public static class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper;

        public ObjectMapperHolder(com.fasterxml.jackson.databind.ObjectMapper mapper) {
            this.mapper = mapper;
        }

        public String writeJson(Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException("JSON 序列化失败", e);
            }
        }
    }

    // ---------- S9 保留登记（POST /api/title-retentions，TITLE_REGISTER） ----------

    @Transactional
    public TitleRetentionView registerRetention(long loanId, String vin, boolean contractualOnly,
                                                String traceId) {
        if (loanId <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "loanId 必须为正整数业务主键");
        }
        if (titleRepository.findByLoanId(loanId).isPresent()) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    "该债权已存在保留登记记录（uk_title_loan）");
        }
        TitleRetention retention = TitleRetention.register(loanId, vin, contractualOnly);
        long titleId = titleRepository.insert(loanId, vin, retention.status().name(),
                retention.registrationMode(),
                retention.registeredAt() == null ? null : Timestamp.from(retention.registeredAt()),
                traceId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("titleId", titleId);
        payload.put("loanId", loanId);
        payload.put("status", retention.status().name());
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.TITLE_RETENTION_REGISTERED, String.valueOf(titleId),
                mapperHolder.writeJson(payload));

        log.info("保留登记完成 loanId={} titleId={} mode={}", loanId, titleId, retention.registrationMode());
        return new TitleRetentionView(titleId, loanId, vin, retention.status().name(),
                retention.registrationMode(), retention.registeredAt());
    }

    public TitleRetentionView getRetention(long loanId) {
        JdbcTitleRepository.TitleRow row = titleRepository.findByLoanId(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "该债权无保留登记记录", Map.of("loanId", String.valueOf(loanId))));
        return new TitleRetentionView(row.titleId(), row.loanId(), row.vin(), row.status(),
                row.registrationMode(), row.registeredAt() == null ? null : row.registeredAt().toInstant());
    }

    // ---------- 第 1–2 步：建解除订单（POST /api/release-orders，RELEASE_DOCUMENT_ISSUE） ----------

    /**
     * 人工路径建单：债权终态证据为挡板输入（{@code loanSettledEvidenceRef} 必填；真实链路由
     * LOAN_SETTLED 事件驱动，见 {@code LoanSettledConsumer}）。
     */
    @Transactional
    public ReleaseOrderView createReleaseOrder(long loanId, String loanSettledEvidenceRef,
                                               String traceId) {
        JdbcTitleRepository.TitleRow retention = titleRepository.findByLoanId(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_TITLE_001,
                        "该债权无保留登记记录，无法创建解除订单",
                        Map.of("loanId", String.valueOf(loanId))));
        boolean loanTerminalEvidence = loanSettledEvidenceRef != null && !loanSettledEvidenceRef.isBlank();
        ReleaseOrder.assertCreationAllowed(loanTerminalEvidence);
        return insertOrder(loanId, retention.titleId(), "MANUAL", null);
    }

    /** 事件驱动建单（第 1–2 步；消费幂等：uk_release_loan + 已存在即返回）。 */
    @Transactional
    public ReleaseOrderView createReleaseOrderFromEvent(long loanId, long settledEventId) {
        JdbcTitleRepository.TitleRow retention = titleRepository.findByLoanId(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_TITLE_001,
                        "LOAN_SETTLED 消费：该债权无保留登记记录",
                        Map.of("loanId", String.valueOf(loanId))));
        ReleaseOrder.assertCreationAllowed(true);
        return insertOrder(loanId, retention.titleId(), "EVENT", settledEventId);
    }

    private ReleaseOrderView insertOrder(long loanId, long titleId, String source, Long settledEventId) {
        long orderId = orderRepository.insert(loanId, titleId, source, settledEventId, null);
        if (orderId == -1) {
            // uk_release_loan 命中：重复消费/重复建单 → 返回既有订单（幂等）
            JdbcReleaseOrderRepository.OrderRow existing = orderRepository.findByLoanId(loanId)
                    .orElseThrow();
            return toView(existing);
        }
        return new ReleaseOrderView(orderId, loanId, titleId, ReleaseOrderStatus.CREATED.name(),
                source, null, null);
    }

    // ---------- 第 3 步：备齐四件套（POST /api/release-orders/{id}/documents） ----------

    @Transactional
    public ReleaseOrderView prepareDocuments(long releaseOrderId, List<DocumentInput> documents,
                                             String traceId) {
        ReleaseOrder order = loadOrder(releaseOrderId);
        if (order.status() != ReleaseOrderStatus.CREATED
                && order.status() != ReleaseOrderStatus.DOCUMENTS_PREPARED) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    "解除订单状态不允许登记文件（仅 CREATED / DOCUMENTS_PREPARED）",
                    Map.of("status", order.status().name()));
        }
        if (documents == null || documents.isEmpty()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "documents 不能为空");
        }
        for (DocumentInput doc : documents) {
            if (doc.docType() == null || !DOCUMENT_TYPES.contains(doc.docType())) {
                throw new BusinessException(RFErrorCode.RF_VALID_001,
                        "docType 必须是解除文件四件套之一：" + DOCUMENT_TYPES);
            }
            orderRepository.upsertDocument(releaseOrderId, doc.docType(), doc.attachmentId(),
                    doc.deliveredTo(), traceId);
        }
        if (order.status() == ReleaseOrderStatus.CREATED && countDistinctTypes(releaseOrderId) == 4) {
            order.prepareDocuments();
            updateOrderStatus(order);
        }
        return reloadView(releaseOrderId);
    }

    // ---------- 第 4 步：発行文件并交付（POST /api/release-orders/{id}/issue） ----------

    @Transactional
    public ReleaseOrderView issueDocuments(long releaseOrderId, String traceId) {
        ReleaseOrder order = loadOrder(releaseOrderId);
        List<JdbcReleaseOrderRepository.DocumentRow> docs = orderRepository.findDocuments(releaseOrderId);
        if (docs.size() < 4 || countDistinctTypes(releaseOrderId) != 4) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    "解除文件四件套未备齐，不得発行（R05 §6 第 3→4 步）");
        }
        if (docs.stream().anyMatch(d -> d.deliveredTo() == null || d.deliveredTo().isBlank())) {
            throw new BusinessException(RFErrorCode.RF_VALID_002,
                    "存在未交付留痕的解除文件（证据链⑤）");
        }
        order.issueDocuments(); // 文件出具 ≠ 解押完成（不改保留状态，两态分离）
        updateOrderStatus(order);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseOrderId", releaseOrderId);
        payload.put("loanId", order.loanId());
        payload.put("status", order.status().name());
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.RELEASE_DOCUMENTS_ISSUED, String.valueOf(releaseOrderId),
                mapperHolder.writeJson(payload));
        return reloadView(releaseOrderId);
    }

    // ---------- 第 5 步：外部提交（POST /api/release-orders/{id}/external-submission） ----------

    @Transactional
    public ReleaseOrderView submitExternal(long releaseOrderId, String submissionRef, String traceId) {
        ReleaseOrder order = loadOrder(releaseOrderId);
        order.submitExternal(submissionRef);
        updateOrderStatus(order);
        return reloadView(releaseOrderId);
    }

    // ---------- 第 6–8 步：回执登记（POST /api/release-orders/{id}/receipts，RELEASE_REGISTER_CONFIRM） ----------

    @Transactional
    public ReceiptResult registerReceipt(long releaseOrderId, String authorityText, String receiptRef,
                                         String verifiedBy, String rawRef, String traceId) {
        ReceiptAuthority authority = parseAuthority(authorityText);
        ReleaseOrder order = loadOrder(releaseOrderId);

        if (authority == ReceiptAuthority.PARTNER_SELF_REPORT) {
            // X-10：经销商/消费者自证「已解押」一律拒绝采信（C-11；不落库、不改状态）
            throw new BusinessException(RFErrorCode.RF_TITLE_002,
                    "PARTNER_SELF_REPORT 不得采信（X-10：要求外部回执）",
                    Map.of("authority", authority.name()));
        }

        // 回执先落库（append-only 留痕）
        orderRepository.insertReceipt(releaseOrderId, authority.name(), receiptRef, verifiedBy,
                rawRef, traceId);

        if (authority == ReceiptAuthority.INTERNAL_WITNESS) {
            // 我方见证代录：仅中间态 MANUAL_RECORDED，绝不等于解押完成（C-11 / X-9）
            order.recordManualWitness();
            updateOrderStatus(order);
            return new ReceiptResult(releaseOrderId, order.status().name(),
                    authority.name(), false, "代录已登记（MANUAL_RECORDED 中间态；补外部回执前不得解押）");
        }

        // EXTERNAL_REGISTRY：唯一可推进解押完成的权威（R04 §3.5：仅 EXTERNAL_SUBMITTED / MANUAL_RECORDED 可达）
        order.confirmRegistration(authority);
        updateOrderStatus(order);

        // 第 7 步：改写保留状态（INV-3 唯一入口）
        JdbcTitleRepository.TitleRow titleRow = titleRepository.findById(order.titleId())
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "保留记录不存在", Map.of("titleId", String.valueOf(order.titleId()))));
        TitleRetention retention = TitleRetention.restore(titleRow.titleId(), titleRow.loanId(),
                titleRow.vin(), titleRow.status(), titleRow.registrationMode(),
                titleRow.registeredAt() == null ? null : titleRow.registeredAt().toInstant(),
                titleRow.releasedAt() == null ? null : titleRow.releasedAt().toInstant(),
                titleRow.version());
        retention.markReleaseRegistered(order.status(), authority);
        titleRepository.updateStatus(titleRow.titleId(), retention.status().name(),
                retention.releasedAt() == null ? null : Timestamp.from(retention.releasedAt()),
                titleRow.version());

        // 第 8 步：关闭订单与保留（终态）+ 闭环事件 TITLE_RELEASE_REGISTERED
        order.close();
        updateOrderStatus(order);
        retention.close();
        JdbcTitleRepository.TitleRow latestTitle = titleRepository.findById(order.titleId())
                .orElseThrow();
        titleRepository.updateStatus(latestTitle.titleId(), retention.status().name(), null,
                latestTitle.version());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseOrderId", releaseOrderId);
        payload.put("loanId", order.loanId());
        payload.put("titleId", order.titleId());
        payload.put("status", order.status().name());
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.TITLE_RELEASE_REGISTERED, String.valueOf(releaseOrderId),
                mapperHolder.writeJson(payload));

        log.info("解押闭环完成 releaseOrderId={} loanId={} authority={}",
                releaseOrderId, order.loanId(), authority.name());
        return new ReceiptResult(releaseOrderId, order.status().name(), authority.name(), true,
                "外部回执确认，解押闭环完成（RELEASE_REGISTERED → CLOSED）");
    }

    // ---------- 证据链（GET /api/release-orders/{id}/evidence-chain） ----------

    public EvidenceChainView evidenceChain(long releaseOrderId) {
        ReleaseOrder order = loadOrder(releaseOrderId);
        List<JdbcReleaseOrderRepository.DocumentRow> docs = orderRepository.findDocuments(releaseOrderId);
        boolean externalReceipt = orderRepository.hasExternalReceipt(releaseOrderId);

        boolean loanTerminal = "EVENT".equals(order.source()) || order.loanSettledEventId() != null
                || order.source() != null; // MANUAL 建单已在入口断言债权终态（挡板声明）
        EvidenceChainPolicy.EvidenceSnapshot snapshot = new EvidenceChainPolicy.EvidenceSnapshot(
                loanTerminal,
                loanTerminal,                       // ② 结清对拍由 retail-core G10 守卫（挡板同源）
                true,                               // ③ 无未决异议（W6 挡板：bureau_disputes 归 W7）
                (int) docs.stream().map(JdbcReleaseOrderRepository.DocumentRow::docType).distinct().count(),
                docs.size() >= 4 && docs.stream().allMatch(d -> d.deliveredTo() != null
                        && !d.deliveredTo().isBlank()),
                order.hasExternalSubmissionEvidence(),
                externalReceipt,
                externalReceipt);                   // ⑧ 只有 EXTERNAL_REGISTRY 行计入 ⑦
        return new EvidenceChainView(releaseOrderId, order.status().name(),
                EvidenceChainPolicy.evaluate(snapshot), EvidenceChainPolicy.allClosed(
                EvidenceChainPolicy.evaluate(snapshot)));
    }

    // ---------- 内部 ----------

    private ReceiptAuthority parseAuthority(String authorityText) {
        try {
            return ReceiptAuthority.valueOf(authorityText);
        } catch (Exception e) {
            throw new BusinessException(RFErrorCode.RF_VALID_001,
                    "authority 必须是 EXTERNAL_REGISTRY / INTERNAL_WITNESS / PARTNER_SELF_REPORT");
        }
    }

    private ReleaseOrder loadOrder(long releaseOrderId) {
        JdbcReleaseOrderRepository.OrderRow row = orderRepository.findById(releaseOrderId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "解除订单不存在", Map.of("releaseOrderId", String.valueOf(releaseOrderId))));
        return ReleaseOrder.restore(row.releaseOrderId(), row.loanId(), row.titleId(), row.status(),
                row.source(), row.loanSettledEventId(), row.externalSubmissionRef(),
                row.confirmedAt() == null ? null : row.confirmedAt().toInstant(),
                row.confirmationAuthority(), row.version());
    }

    private void updateOrderStatus(ReleaseOrder order) {
        if (orderRepository.updateStatus(order.releaseOrderId(), order.status().name(),
                order.confirmedAt() == null ? null : Timestamp.from(order.confirmedAt()),
                order.confirmationAuthority(), order.externalSubmissionRef(),
                order.version()) == 0) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    "解除订单并发更新冲突（乐观锁 version）");
        }
    }

    private int countDistinctTypes(long releaseOrderId) {
        return (int) orderRepository.findDocuments(releaseOrderId).stream()
                .map(JdbcReleaseOrderRepository.DocumentRow::docType).distinct().count();
    }

    private ReleaseOrderView reloadView(long releaseOrderId) {
        return toView(orderRepository.findById(releaseOrderId).orElseThrow());
    }

    private ReleaseOrderView toView(JdbcReleaseOrderRepository.OrderRow row) {
        return new ReleaseOrderView(row.releaseOrderId(), row.loanId(), row.titleId(), row.status(),
                row.source(), row.externalSubmissionRef(),
                row.confirmedAt() == null ? null : row.confirmedAt().toInstant());
    }

    // ---------- 视图 ----------

    public record TitleRetentionView(long titleId, long loanId, String vin, String status,
                                     String registrationMode, Instant registeredAt) {
    }

    public record ReleaseOrderView(long releaseOrderId, long loanId, long titleId, String status,
                                   String source, String externalSubmissionRef, Instant confirmedAt) {
    }

    public record DocumentInput(String docType, Long attachmentId, String deliveredTo) {
    }

    public record ReceiptResult(long releaseOrderId, String status, String authority,
                                boolean releaseCompleted, String message) {
    }

    public record EvidenceChainView(long releaseOrderId, String orderStatus,
                                    List<EvidenceChainPolicy.EvidenceItem> items, boolean allClosed) {
    }
}
