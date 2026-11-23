package com.company.retail.contract.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeCipher;
import com.company.retail.common.messaging.EnvelopePublisher;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.contract.domain.Contract;
import com.company.retail.contract.domain.ContractDomainException;
import com.company.retail.contract.domain.ContractPolicy;
import com.company.retail.contract.domain.ContractStatus;
import com.company.retail.contract.domain.StatutoryDocument;
import com.company.retail.contract.domain.TransactionScheme;
import com.company.retail.contract.persistence.ContractRepository;
import com.company.retail.contract.persistence.StatutoryDocumentRepository;
import com.company.retail.domain.exception.RFErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合同域用例编排（事务边界只在这里，一个用例一个事务；事件与业务写同一本地事务，
 * {@link OutboxPublisher} 实现不自带 @Transactional——RfJdbcOutboxPublisher 事务口径）。
 *
 * <p>C-08：法定书面文件只做交付动作留痕与状态记录，不实现书面文件效力判断；
 * 冷静期 8 日占位参数可配，不实现冷静期效力判断。</p>
 *
 * <p><b>ADR-RF-014 削峰分支</b>：MQ 开启时，送达通道受理推送改为「封密文信封入队」，
 * 由消费端 {@link com.company.retail.contract.messaging.ContractDeliveryEnvelopeConsumer}
 * 解密后执行<b>同一个</b> {@link DeliveryHandler} 动作（同步与异步只此一份业务逻辑）；
 * 通道未启用时行为与原来逐字一致（事务边界、状态推进、返回值均不变）。</p>
 */
@Service
public class ContractApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ContractApplicationService.class);

    private final ContractRepository contractRepository;
    private final StatutoryDocumentRepository documentRepository;
    private final ContractPolicy policy;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** 送达动作处理器（同步路径与 MQ 消费端共用同一份业务动作，ADR-RF-014）。 */
    private final DeliveryHandler deliveryHandler;
    /** MQ 通道（ADR-RF-014：缺 bean 或未启用 → 走同步路径，行为与原来逐字一致）。 */
    private final ObjectProvider<EnvelopePublisher> publisherProvider;
    private final ObjectProvider<EnvelopeCipher> cipherProvider;

    public ContractApplicationService(ContractRepository contractRepository,
                                      StatutoryDocumentRepository documentRepository,
                                      ContractPolicy policy,
                                      OutboxPublisher outbox,
                                      ObjectMapper objectMapper,
                                      DeliveryHandler deliveryHandler,
                                      ObjectProvider<EnvelopePublisher> publisherProvider,
                                      ObjectProvider<EnvelopeCipher> cipherProvider) {
        this.contractRepository = contractRepository;
        this.documentRepository = documentRepository;
        this.policy = policy;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = Clock.system(Contract.JST);
        this.deliveryHandler = deliveryHandler;
        this.publisherProvider = publisherProvider;
        this.cipherProvider = cipherProvider;
    }

    /** POST /api/contracts：创建合同（DRAFT）。uk_contract_app 幂等兜底：一个申请一份合同。 */
    @Transactional
    public ContractView create(long applicationId, long customerId, String transactionScheme, String traceId) {
        TransactionScheme scheme = parseScheme(transactionScheme);
        if (applicationId <= 0 || customerId <= 0) {
            throw ContractDomainException.invalid("applicationId/customerId 必须为正整数业务主键");
        }
        Contract contract = Contract.draft(applicationId, customerId, scheme);
        try {
            String snapshot = writeJson(contract.termsSnapshot(policy));
            long id = contractRepository.insert(contract, snapshot, traceId);
            return view(contractRepository.findById(id).orElseThrow());
        } catch (DuplicateKeyException e) {
            throw ContractDomainException.conflict("该申请已存在合同（uk_contract_app）");
        }
    }

    /** POST /api/contracts/{id}/documents：法定书面文件交付留痕（append-only，不物理删除）。 */
    @Transactional
    public long deliverDocument(long contractId, String docType, String templateVersion,
                                String deliveryMethod, Long attachmentId, String traceId) {
        Contract contract = load(contractId);
        if (contract.status() == ContractStatus.CANCELLED || contract.status() == ContractStatus.TERMINATED) {
            throw ContractDomainException.illegalState("终态合同不得追加交付留痕：" + contract.status());
        }
        if (docType == null || docType.isBlank() || deliveryMethod == null || deliveryMethod.isBlank()) {
            throw ContractDomainException.invalid("docType/deliveryMethod 必填（交付动作留痕口径）");
        }
        StatutoryDocument document = new StatutoryDocument(null, contractId, docType, templateVersion,
                Instant.now(clock), deliveryMethod, attachmentId, null);
        long documentId = documentRepository.insertDelivery(document, traceId);
        // 送达动作：MQ 开启 → 封密文入队（通道调用移出事务线程，削峰）；未启用 → 就地同步送达
        // （W-SIM2：咨询性副本，失败只记日志不中断留痕主流程；权威留痕=本表）
        DeliveryHandler.DeliveryCommand command = DeliveryHandler.DeliveryCommand.of(
                contractId, docType, deliveryMethod, templateVersion, attachmentId, traceId);
        if (!enqueueDelivery(command)) {
            deliveryHandler.deliver(command);
        }
        return documentId;
    }

    /**
     * 削峰分支：把送达载荷封成密文信封投进 MQ（ADR-RF-014）。
     *
     * <p>broker 上只允许出现信封（密文 + 非个人信息业务键）；载荷明文只存在于密文内部。</p>
     *
     * @return true = 已入队（调用方跳过同步送达）；false = MQ 未启用（调用方走同步路径）
     */
    private boolean enqueueDelivery(DeliveryHandler.DeliveryCommand command) {
        EnvelopePublisher publisher = publisherProvider == null ? null : publisherProvider.getIfAvailable();
        if (publisher == null || !publisher.enabled()) {
            return false;
        }
        EnvelopeCipher cipher = cipherProvider == null ? null : cipherProvider.getIfAvailable();
        if (cipher == null) {
            throw new IllegalStateException("MQ 通道已启用但信封加解密器缺失，拒绝降级为同步送达");
        }
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.DELIVERY,
                String.valueOf(command.contractId()),
                DeliveryHandler.payloadJson(command), command.traceId());
        publisher.publish(EnvelopeTopics.DELIVERY, envelope);
        log.info("送达已封密文入队 contractId={} docType={} envelopeId={}",
                command.contractId(), command.docType(), envelope.envelopeId());
        return true;
    }

    /** DOCUMENTS_DELIVERED：交付留痕齐全后推进（模板按 C-08 配置选定）。 */
    @Transactional
    public ContractView markDocumentsDelivered(long contractId, String traceId) {
        Contract contract = load(contractId);
        List<StatutoryDocument> documents = documentRepository.findByContract(contractId);
        try {
            contract.markDocumentsDelivered(policy, documents, clock);
        } catch (IllegalStateException e) {
            throw ContractDomainException.illegalState(e.getMessage());
        }
        contractRepository.updateStatus(contract);
        return view(contractRepository.findById(contractId).orElseThrow());
    }

    /** POST /api/contracts/{id}/sign：签订 → SIGNED → COOLING_OFF / EFFECTIVE（同一事务）。 */
    @Transactional
    public ContractView sign(long contractId, String traceId) {
        Contract contract = load(contractId);
        List<StatutoryDocument> documents = documentRepository.findByContract(contractId);
        Instant signedAt = Instant.now(clock);
        try {
            contract.sign(policy, documents, clock, signedAt);
        } catch (IllegalStateException e) {
            throw ContractDomainException.illegalState(e.getMessage());
        }
        int updated = contractRepository.updateAfterSigning(contract, writeJson(contract.termsSnapshot(policy)));
        if (updated == 0) {
            throw ContractDomainException.conflict("合同并发更新冲突（乐观锁 version）");
        }
        // CONTRACT_SIGNED（R09 §5.1 聚合键 contractId；payload 只带业务主键，过白名单守卫）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contractId);
        payload.put("applicationId", contract.applicationId());
        payload.put("status", contract.status().name());
        payload.put("traceId", traceId);
        outbox.publish(OutboxPublisher.CONTRACT_SIGNED, String.valueOf(contractId), writeJson(payload));
        return view(contractRepository.findById(contractId).orElseThrow());
    }

    /** POST /api/contracts/{id}/cooling-off/confirm：COOLING_OFF → EFFECTIVE（占位流转，不实现效力判断）。 */
    @Transactional
    public ContractView confirmCoolingOff(long contractId, String traceId) {
        Contract contract = load(contractId);
        try {
            contract.confirmCoolingOffExpired(clock);
        } catch (IllegalStateException e) {
            throw ContractDomainException.illegalState(e.getMessage());
        }
        contractRepository.updateStatus(contract);
        return view(contractRepository.findById(contractId).orElseThrow());
    }

    /** POST /api/contracts/{id}/cancel：DOCUMENTS_DELIVERED / COOLING_OFF → CANCELLED。 */
    @Transactional
    public ContractView cancel(long contractId, String traceId) {
        Contract contract = load(contractId);
        try {
            contract.cancel();
        } catch (IllegalStateException e) {
            throw ContractDomainException.illegalState(e.getMessage());
        }
        contractRepository.updateStatus(contract);
        return view(contractRepository.findById(contractId).orElseThrow());
    }

    /** GET /api/contracts/{id}/disbursement-gate：G8 合同门禁（fail-closed 判定源，retail-core 消费）。 */
    public GateResult disbursementGate(long contractId) {
        return contractRepository.findById(contractId)
                .map(c -> c.isEffective()
                        ? GateResult.allowed("CONTRACT_EFFECTIVE")
                        : GateResult.denied("CONTRACT_NOT_EFFECTIVE", c.status().name()))
                .orElse(GateResult.denied("CONTRACT_NOT_FOUND", null));
    }

    public ContractView get(long contractId) {
        return view(load(contractId));
    }

    private Contract load(long contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "合同不存在", Map.of("contractId", String.valueOf(contractId))));
    }

    private static TransactionScheme parseScheme(String value) {
        try {
            return TransactionScheme.valueOf(value);
        } catch (Exception e) {
            throw ContractDomainException.invalid(
                    "transactionScheme 必须是 INSTALLMENT_CREDIT / MONEY_LENDING（C-01 双形态）");
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("payload JSON 序列化失败", e);
        }
    }

    private static ContractView view(Contract c) {
        return new ContractView(c.contractId(), c.applicationId(), c.customerId(), c.scheme().name(),
                c.status().name(), c.signedAt(), c.effectiveAt(), c.coolingOffUntil(), c.version());
    }

    /** 门禁判定结果（形状对齐 frozen FeignContracts.GateResult：allowed + reasonCode）。 */
    public record GateResult(boolean allowed, String reasonCode) {
        static GateResult allowed(String reason) {
            return new GateResult(true, reason);
        }

        static GateResult denied(String reason, String status) {
            return new GateResult(false, reason);
        }
    }

    public record ContractView(long contractId, long applicationId, long customerId, String transactionScheme,
                               String status, Instant signedAt, Instant effectiveAt,
                               java.time.LocalDate coolingOffUntil, int version) {
    }
}
