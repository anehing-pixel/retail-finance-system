package com.company.retail.customer.app;

import com.company.retail.customer.domain.IdentityVerification;
import com.company.retail.customer.repo.IdentityVerificationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 身份核验材料登记动作（C-07；ADR-RF-014 削峰分叉点）。
 *
 * <p><b>为什么抽成 handler</b>：同步路径（控制器就地登记）与 MQ 路径（密文信封消费端登记）
 * 必须走<b>同一份</b>业务动作——写 pii.identity_documents + 把核验单状态推进到
 * DOCUMENTS_RECEIVED。两份实现就是两条口径，迟早漂移（ADR-RF-014 §2 铁律）。</p>
 *
 * <p><b>个人信息</b>：脱敏号与证件有效期属于个人信息（B-1），只允许出现在密文信封内部，
 * 禁止进日志与异常消息；本类日志与异常一律不回显载荷内容。</p>
 */
@Service
public class IdentityDocumentHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 材料登记命令（同步入口与 MQ 消费端共用同一份形状）。
     *
     * @param verificationId 核验单 ID（业务主键，非个人信息）
     * @param documentType   材料类型
     * @param attachmentId   附件引用（可空）
     * @param expiryDate     证件有效期
     * @param maskedNumber   证件号脱敏值（个人信息，只进密文）
     * @param traceId        链路追踪 ID
     */
    public record DocumentCommand(long verificationId, String documentType, Long attachmentId,
                                  LocalDate expiryDate, String maskedNumber, String traceId) {
    }

    private final IdentityVerificationRepository verifications;

    public IdentityDocumentHandler(IdentityVerificationRepository verifications) {
        this.verifications = verifications;
    }

    /**
     * 材料登记：写 pii.identity_documents + 推进核验单状态。
     *
     * <p>状态随材料推进（PENDING → DOCUMENTS_RECEIVED）；补件不迁移。
     * 写库失败一律抛出——登记失败不能被吞掉（消费端按重试口径处理）。</p>
     *
     * @param command 登记命令（不得为 null）
     */
    public void register(DocumentCommand command) {
        if (command == null) {
            return;
        }
        IdentityVerification agg = verifications.load(command.verificationId());
        agg.submitDocument(new IdentityVerification.Document(0, command.documentType(), command.attachmentId(),
                command.expiryDate(), command.maskedNumber()), LocalDate.now());
        verifications.insertDocument(command.verificationId(), command.documentType(), command.attachmentId(),
                command.expiryDate(), command.maskedNumber(), command.traceId());
        verifications.updateStatus(command.verificationId(), agg.getStatus(), null, null, null);
    }

    /** 材料登记载荷 → JSON（生产端：只进密文信封内部，broker 上看不到明文）。 */
    public static String payloadJson(DocumentCommand command) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verificationId", command.verificationId());
        m.put("documentType", nullToEmpty(command.documentType()));
        m.put("attachmentId", command.attachmentId());
        m.put("expiryDate", command.expiryDate() == null ? null : command.expiryDate().toString());
        m.put("maskedNumber", nullToEmpty(command.maskedNumber()));
        m.put("traceId", nullToEmpty(command.traceId()));
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("核验材料载荷序列化失败", e);
        }
    }

    /**
     * 材料登记载荷 JSON → 命令（消费端）。
     *
     * <p>非法载荷抛 {@link IllegalArgumentException}：不吞异常，交由 broker 重投
     * （重试上限由消费端机制层控制）。异常消息不含载荷内容。</p>
     */
    public static DocumentCommand fromPayloadJson(String payloadJson) {
        JsonNode node;
        try {
            node = MAPPER.readTree(payloadJson == null ? "" : payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("核验材料信封载荷不是合法 JSON", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("核验材料信封载荷不是 JSON 对象");
        }
        long verificationId = node.path("verificationId").asLong(0L);
        if (verificationId <= 0) {
            throw new IllegalArgumentException("核验材料信封载荷缺 verificationId");
        }
        String documentType = node.path("documentType").asText("");
        if (documentType.isBlank()) {
            throw new IllegalArgumentException("核验材料信封载荷缺 documentType");
        }
        Long attachmentId = node.hasNonNull("attachmentId") ? node.path("attachmentId").asLong() : null;
        String expiryText = node.path("expiryDate").asText("");
        LocalDate expiryDate = expiryText.isBlank() ? null : LocalDate.parse(expiryText);
        return new DocumentCommand(verificationId, documentType, attachmentId, expiryDate,
                node.path("maskedNumber").asText(""), node.path("traceId").asText(""));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
