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
import com.company.retail.titlerelease.persistence.AttachmentRepository;
import com.company.retail.titlerelease.persistence.ReleaseOrderRepository;
import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
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

    private final TitleRepositoryPort titleRepository;
    private final ReleaseOrderRepository orderRepository;
    private final AttachmentRepository attachmentRepository;
    private final LocalStorageAttachmentStorage attachmentStorage;
    private final OutboxPublisher outbox;
    private final ObjectMapperHolder mapperHolder;

    public TitleReleaseApplicationService(TitleRepositoryPort titleRepository,
                                          ReleaseOrderRepository orderRepository,
                                          AttachmentRepository attachmentRepository,
                                          LocalStorageAttachmentStorage attachmentStorage,
                                          OutboxPublisher outbox,
                                          ObjectMapperHolder mapperHolder) {
        this.titleRepository = titleRepository;
        this.orderRepository = orderRepository;
        this.attachmentRepository = attachmentRepository;
        this.attachmentStorage = attachmentStorage;
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
        java.util.Optional<TitleRepositoryPort.TitleRow> existing = titleRepository.findByLoanId(loanId);
        if (existing.isPresent()) {
            TitleRepositoryPort.TitleRow row = existing.get();
            if (TitleRetention.isPlaceholder(loanId, row.vin())) {
                // 挡板占位行 → 正式登记：订正 VIN 并补发一次登记事件（用户裁决 2026-11-23）。
                return correctPlaceholderRegistration(row, vin, contractualOnly, traceId);
            }
            // 幂等返回（W-SIM2）：重复登记返回既有记录而非 409，且不重复发布登记事件
            // （与 createReleaseOrderFromEvent 的消费幂等同款语义）。
            log.info("保留登记已存在，幂等返回 loanId={} titleId={} vin={}",
                    loanId, row.titleId(), row.vin());
            return toRetentionView(row);
        }
        TitleRetention retention = TitleRetention.register(loanId, vin, contractualOnly);
        long titleId = titleRepository.insert(loanId, vin, retention.status().name(),
                retention.registrationMode(),
                retention.registeredAt() == null ? null : Timestamp.from(retention.registeredAt()),
                traceId);
        if (titleId == 0) {
            // 并发兜底（uk_title_loan）：LOAN_SETTLED 兜底建单抢在本次 INSERT 之前提交。
            // 该分支曾以 500 暴露：唯一键异常直接冒泡（前端冒烟 F11 偶发失败）。
            TitleRepositoryPort.TitleRow row = titleRepository.findByLoanId(loanId)
                    .orElseThrow(() -> new BusinessException(RFErrorCode.RF_SYS_001,
                            Map.of("reason", "TITLE_RETENTION_CONFLICT")));
            if (TitleRetention.isPlaceholder(loanId, row.vin())) {
                // 对方建的是挡板占位行 → 本次正式登记订正它并补发事件。
                return correctPlaceholderRegistration(row, vin, contractualOnly, traceId);
            }
            log.info("保留登记并发冲突（uk_title_loan），幂等返回既有记录 loanId={} titleId={} vin={}",
                    loanId, row.titleId(), row.vin());
            return toRetentionView(row);
        }

        publishRetentionRegistered(titleId, loanId, retention.status().name(), traceId);

        log.info("保留登记完成 loanId={} titleId={} mode={}", loanId, titleId, retention.registrationMode());
        return new TitleRetentionView(titleId, loanId, vin, retention.status().name(),
                retention.registrationMode(), retention.registeredAt());
    }

    /**
     * 登记订正：把挡板占位行（{@code vin=UNKNOWN-<loanId>}）订正为正式登记，并**补发一次**登记事件。
     *
     * <p>为什么补发（用户裁决 2026-11-23）：占位行只是链路挡板，不是「已登记」的业务事实；
     * 不补发则证据链缺「所有权保留登记」这一环（E2E S11 的 TITLE_RETENTION_REGISTERED 断言会失败）。</p>
     *
     * <p>为什么只补发一次：订正走乐观锁 + 占位 VIN 条件（{@code version} + {@code vin=占位值}），
     * 影响行数为 1 的那一个调用方才补发；并发/重放落到 0 行 → 重读既有行幂等返回，
     * 不重复发事件（R09 §5.4 幂等铁律）。</p>
     */
    private TitleRetentionView correctPlaceholderRegistration(TitleRepositoryPort.TitleRow row, String vin,
                                                              boolean contractualOnly, String traceId) {
        TitleRetention retention = TitleRetention.restore(row.titleId(), row.loanId(), row.vin(), row.status(),
                row.registrationMode(),
                row.registeredAt() == null ? null : row.registeredAt().toInstant(),
                row.releasedAt() == null ? null : row.releasedAt().toInstant(),
                row.version());
        retention.correctRegistration(vin, contractualOnly);   // 领域不变量：状态机守卫
        int affected = titleRepository.correctPlaceholderRegistration(row.titleId(),
                TitleRetention.placeholderVin(row.loanId()), retention.vin(), retention.registrationMode(),
                retention.registeredAt() == null ? null : Timestamp.from(retention.registeredAt()),
                row.version());
        if (affected == 0) {
            // 并发：对方已订正 → 重读幂等返回，不补发事件。
            TitleRepositoryPort.TitleRow latest = titleRepository.findByLoanId(row.loanId())
                    .orElseThrow(() -> new BusinessException(RFErrorCode.RF_SYS_001,
                            Map.of("reason", "TITLE_RETENTION_CORRECTION_RACE")));
            log.info("保留登记订正并发：对方已订正，幂等返回 loanId={} titleId={} vin={}",
                    row.loanId(), latest.titleId(), latest.vin());
            return toRetentionView(latest);
        }
        log.info("保留登记订正完成（挡板占位行 → 正式登记）loanId={} titleId={} vin={} → {}",
                row.loanId(), row.titleId(), row.vin(), retention.vin());
        publishRetentionRegistered(row.titleId(), row.loanId(), retention.status().name(), traceId);
        return new TitleRetentionView(row.titleId(), row.loanId(), retention.vin(),
                retention.status().name(), retention.registrationMode(), retention.registeredAt());
    }

    /** 发布 TITLE_RETENTION_REGISTERED（登记事件；payload 无个人信息，INV-4）。 */
    private void publishRetentionRegistered(long titleId, long loanId, String status, String traceId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("titleId", titleId);
        payload.put("loanId", loanId);
        payload.put("status", status);
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.TITLE_RETENTION_REGISTERED, String.valueOf(titleId),
                mapperHolder.writeJson(payload));
    }

    /**
     * LOAN_SETTLED 兜底：缺保留登记记录时按挡板补 REGISTERED（W6 挡板清单）。
     *
     * <p>并发语义：操作员主动登记与本兜底建单撞 uk_title_loan 时，insert 返回 0（对方已建）
     * → 幂等跳过，不抛异常（正常竞争，不是坏事件，不进 DEAD）。</p>
     */
    public void ensureRetentionForSettledLoan(long loanId) {
        if (titleRepository.findByLoanId(loanId).isPresent()) {
            return;
        }
        long titleId = titleRepository.insert(loanId, TitleRetention.placeholderVin(loanId), "REGISTERED", "REGISTERED",
                Timestamp.from(Instant.now()), null);
        if (titleId == 0) {
            log.info("保留登记兜底建单并发冲突（uk_title_loan），对方已建，跳过 loanId={}", loanId);
            return;
        }
        log.warn("LOAN_SETTLED 消费：债权 {} 缺保留登记记录，已按挡板补 REGISTERED（W6 兜底）", loanId);
    }

    /** 保留登记行 → 视图（读模型收敛一处）。 */
    private TitleRetentionView toRetentionView(TitleRepositoryPort.TitleRow row) {
        return new TitleRetentionView(row.titleId(), row.loanId(), row.vin(), row.status(),
                row.registrationMode(), row.registeredAt() == null ? null : row.registeredAt().toInstant());
    }

    public TitleRetentionView getRetention(long loanId) {
        TitleRepositoryPort.TitleRow row = titleRepository.findByLoanId(loanId)
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
        TitleRepositoryPort.TitleRow retention = titleRepository.findByLoanId(loanId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_TITLE_001,
                        "该债权无保留登记记录，无法创建解除订单",
                        Map.of("loanId", String.valueOf(loanId))));
        boolean loanTerminalEvidence = loanSettledEvidenceRef != null && !loanSettledEvidenceRef.isBlank();
        ReleaseOrder.assertCreationAllowed(loanTerminalEvidence);
        return insertOrder(loanId, retention.titleId(), "MANUAL", null);
    }

    /** 按 loanId 只读查询解除订单（供运营台/前端在事件建单后定位订单；无订单返回 null 而非 404）。 */
    public ReleaseOrderView findOrderByLoanId(long loanId) {
        return orderRepository.findByLoanId(loanId).map(this::toView).orElse(null);
    }

    /** 事件驱动建单（第 1–2 步；消费幂等：uk_release_loan + 已存在即返回）。 */
    @Transactional
    public ReleaseOrderView createReleaseOrderFromEvent(long loanId, long settledEventId) {
        TitleRepositoryPort.TitleRow retention = titleRepository.findByLoanId(loanId)
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
            ReleaseOrderRepository.OrderRow existing = orderRepository.findByLoanId(loanId)
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
        List<ReleaseOrderRepository.DocumentRow> docs = orderRepository.findDocuments(releaseOrderId);
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

        // W9-C：解押文件落本地存储并登记 core.attachments（ADR-RF-008）——
        // 出具时点生成文件本体；已有 attachmentId（人工上传路径）不覆盖。
        storeReleaseDocuments(releaseOrderId, docs, traceId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseOrderId", releaseOrderId);
        payload.put("loanId", order.loanId());
        payload.put("status", order.status().name());
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.RELEASE_DOCUMENTS_ISSUED, String.valueOf(releaseOrderId),
                mapperHolder.writeJson(payload));
        return reloadView(releaseOrderId);
    }

    // ---------- W9-C：解押文件本体落本地存储 + core.attachments 登记 ----------

    /**
     * 出具时点把每份解押文件写入本地存储（ADR-RF-008，{@code retail.attachments.base-dir}）
     * 并登记 {@code core.attachments}（biz_type=biz_id=release_order 关联），
     * 回填 release_documents.attachment_id——W6 缺口收口：引用位落到实体附件。
     *
     * <p>幂等：已登记（attachmentId 非空）的文件不重复生成/覆盖。
     * 文件内容为文本版解押凭证（阶段实现），只含订单/债权标识与状态，不含客户 PII。</p>
     */
    private void storeReleaseDocuments(long releaseOrderId,
                                       List<ReleaseOrderRepository.DocumentRow> docs,
                                       String traceId) {
        for (ReleaseOrderRepository.DocumentRow doc : docs) {
            if (doc.attachmentId() != null) {
                continue;
            }
            byte[] content = renderReleaseDocument(releaseOrderId, doc, traceId);
            String objectKey = LocalStorageAttachmentStorage.buildObjectKey(
                    "release-order", String.valueOf(releaseOrderId), doc.docType(), ".txt");
            String sha256 = attachmentStorage.put(objectKey, content);
            long attachmentId = attachmentRepository.insert("release_order",
                    String.valueOf(releaseOrderId), objectKey, sha256, content.length,
                    uploadedBy(), traceId);
            orderRepository.attachDocument(doc.documentId(), attachmentId);
            log.info("解押文件已落本地存储 orderId={} docType={} attachmentId={} sha256={}",
                    releaseOrderId, doc.docType(), attachmentId, sha256);
        }
    }

    /** 文本版解押文件渲染（不含客户 PII：只有订单/债权标识、状态、交付留痕）。 */
    private static byte[] renderReleaseDocument(long releaseOrderId,
                                                ReleaseOrderRepository.DocumentRow doc,
                                                String traceId) {
        String body = "RELEASE DOCUMENT\n"
                + "docType=" + doc.docType() + "\n"
                + "releaseOrderId=" + releaseOrderId + "\n"
                + "documentId=" + doc.documentId() + "\n"
                + "status=DOCUMENTS_ISSUED\n"
                + "deliveredTo=" + doc.deliveredTo() + "\n"
                + "issuedAt=" + (doc.issuedAt() == null ? "" : doc.issuedAt().toInstant().toString()) + "\n"
                + "traceId=" + traceId + "\n";
        return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 审计 actor 取验签身份（B-9 同构；无请求上下文按 SYSTEM）。 */
    private static String uploadedBy() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "SYSTEM";
        }
        Object subject = attrs.getAttribute("authSubject",
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return subject == null || String.valueOf(subject).isBlank() ? "SYSTEM" : String.valueOf(subject);
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

        // 第 7 步：改写保留状态（INV-3 唯一入口）
        TitleRepositoryPort.TitleRow titleRow = titleRepository.findById(order.titleId())
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

        // 第 8 步：关闭订单与保留（终态）+ 闭环事件 TITLE_RELEASE_REGISTERED。
        // REGISTRATION_CONFIRMED → CLOSED 的两段迁移各自过内存状态机守卫，DB 合并为一次写
        //（避免同一 version 上的二次乐观锁更新冲突）。
        order.close();
        retention.close();
        updateOrderStatus(order);
        TitleRepositoryPort.TitleRow latestTitle = titleRepository.findById(order.titleId())
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
        List<ReleaseOrderRepository.DocumentRow> docs = orderRepository.findDocuments(releaseOrderId);
        boolean externalReceipt = orderRepository.hasExternalReceipt(releaseOrderId);

        boolean loanTerminal = "EVENT".equals(order.source()) || order.loanSettledEventId() != null
                || order.source() != null; // MANUAL 建单已在入口断言债权终态（挡板声明）
        EvidenceChainPolicy.EvidenceSnapshot snapshot = new EvidenceChainPolicy.EvidenceSnapshot(
                loanTerminal,
                loanTerminal,                       // ② 结清对拍由 retail-core G10 守卫（挡板同源）
                true,                               // ③ 无未决异议（W6 挡板：bureau_disputes 归 W7）
                (int) docs.stream().map(ReleaseOrderRepository.DocumentRow::docType).distinct().count(),
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
        ReleaseOrderRepository.OrderRow row = orderRepository.findById(releaseOrderId)
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
                .map(ReleaseOrderRepository.DocumentRow::docType).distinct().count();
    }

    private ReleaseOrderView reloadView(long releaseOrderId) {
        return toView(orderRepository.findById(releaseOrderId).orElseThrow());
    }

    private ReleaseOrderView toView(ReleaseOrderRepository.OrderRow row) {
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
