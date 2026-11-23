package com.company.retail.titlerelease.web;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.storage.DirectUploadEndpoint;
import com.company.platform.storage.AttachmentStorage.ObjectMetadata;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.common.messaging.CipherEnvelope;
import com.company.retail.common.messaging.EnvelopeCipher;
import com.company.retail.common.messaging.EnvelopePublisher;
import com.company.retail.common.messaging.EnvelopeTopics;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.domain.model.Permission;
import com.company.retail.titlerelease.application.AttachmentUploadService;
import com.company.retail.titlerelease.messaging.AttachmentEnvelopePayload;
import com.company.retail.titlerelease.persistence.AttachmentRepository;
import com.company.retail.titlerelease.persistence.AttachmentRepository.AttachmentRow;
import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 附件端点（ADR-RF-008：本地文件系统存储；W9-C 接线；ADR-RF-014：密文信封削峰）。
 *
 * <p>平台 {@link DirectUploadEndpoint} 机制下，本地实现由应用提供上传/下载端点
 * （本地磁盘没有对象存储直传，S3 实现无需本端点）。用 {@link ObjectProvider} 注入端口，
 * web 层不依赖具体实现类。</p>
 *
 * <ul>
 *   <li>POST /api/attachments/presign —— 签发上传授权（锁定 key/类型/长度/时效）；</li>
 *   <li>PUT  /api/attachments/local-upload —— 预签名直传（员工 + HMAC 授权绑定 key/长度/时效）；
 *       <b>MQ 开启时</b>：授权校验通过后把字节加密成密文信封投进 MQ，落盘由消费端异步做
 *       （ADR-RF-014「控制器把密文扔进 MQ」），超限文件回落同步直传；</li>
 *   <li>POST /api/attachments/register —— 回读元数据（SHA-256 对拍）登记 core.attachments；
 *       <b>MQ 开启时</b>：优先用消费端登记的「待登记」占位行转正，消费者尚未落盘则返回
 *       HTTP 202 受理（客户端重试 register 即可，重复调用不产生第二行）；</li>
 *   <li>GET  /api/attachments/local-download —— 预签名下载（员工 + HMAC 授权）；</li>
 *   <li>GET  /api/attachments/{id} —— 受控下载（员工权限 + RELEASE_DOCUMENT_ISSUE，
 *       按 core.attachments 行回读；响应不回显任何客户 PII，文件名只用对象键）。</li>
 * </ul>
 *
 * <p><b>三条上传分支</b>：① MQ 关闭 → 同步直传（既有行为完全不变）；
 * ② MQ 开启且文件未超内联上限 → 投 MQ 立即受理（不落盘）；
 * ③ MQ 开启但文件超上限（{@code retail.mq.attachment.max-inline-bytes}，默认 2MB）→
 * 回落同步直传（行为不变）。</p>
 */
@RestController
@RequireSubject(com.company.platform.security.SubjectType.EMPLOYEE)
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    /**
     * 内联上传默认上限：2MB。
     *
     * <p>RocketMQ 单消息默认上限 4MB，base64 载荷膨胀约 1/3，超限文件必须回落同步直传。</p>
     */
    public static final long DEFAULT_MAX_INLINE_BYTES = 2L * 1024 * 1024;

    private final ObjectProvider<DirectUploadEndpoint> endpointProvider;
    private final ObjectProvider<LocalStorageAttachmentStorage> storageProvider;
    private final AttachmentRepository attachmentRepository;
    private final AttachmentUploadService uploadService;
    private final ObjectProvider<EnvelopePublisher> publisherProvider;
    private final ObjectProvider<EnvelopeCipher> cipherProvider;
    private final long maxInlineBytes;

    /**
     * 既有装配（MQ 能力缺席）：全部走同步路径。
     *
     * <p>保留本构造器是为了不改动既有装配与既有调用签名；MQ 相关依赖缺席时
     * {@code mqEnabled()} 恒为 false，行为与改造前完全一致。</p>
     */
    public AttachmentController(ObjectProvider<DirectUploadEndpoint> endpointProvider,
                                ObjectProvider<LocalStorageAttachmentStorage> storageProvider,
                                AttachmentRepository attachmentRepository) {
        this(endpointProvider, storageProvider, attachmentRepository, null, null,
                DEFAULT_MAX_INLINE_BYTES);
    }

    /**
     * 完整装配：接入 MQ 密文信封通道（ADR-RF-014）。
     *
     * @param maxInlineBytes 可内联进 MQ 的文件字节上限（{@code retail.mq.attachment.max-inline-bytes}，
     *                        环境变量 {@code RETAIL_MQ_ATTACHMENT_MAX_INLINE_BYTES}，默认 2MB）
     */
    @Autowired
    public AttachmentController(ObjectProvider<DirectUploadEndpoint> endpointProvider,
                                ObjectProvider<LocalStorageAttachmentStorage> storageProvider,
                                AttachmentRepository attachmentRepository,
                                ObjectProvider<EnvelopePublisher> publisherProvider,
                                ObjectProvider<EnvelopeCipher> cipherProvider,
                                @Value("${retail.mq.attachment.max-inline-bytes:"
                                        + DEFAULT_MAX_INLINE_BYTES + "}") long maxInlineBytes) {
        this.endpointProvider = endpointProvider;
        this.storageProvider = storageProvider;
        this.attachmentRepository = attachmentRepository;
        this.uploadService = new AttachmentUploadService(endpointProvider, attachmentRepository);
        this.publisherProvider = publisherProvider;
        this.cipherProvider = cipherProvider;
        this.maxInlineBytes = maxInlineBytes;
    }

    private static String traceId() {
        return RfSecurityConfig.traceId(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes());
    }

    /** 签发上传授权（本地实现：授权 URL 指向本应用直传端点，签名锁定单对象+长度+时效）。 */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @PostMapping("/api/attachments/presign")
    public ApiResponse<Map<String, Object>> presign(@RequestBody PresignRequest req) {
        LocalStorageAttachmentStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "当前存储实现不支持预签名");
        }
        if (req.contentLength() > storage.maxSizeBytes()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "声明大小超出附件大小限制");
        }
        var presigned = storage.presignUpload(req.objectKey(), req.contentType(),
                req.contentLength(), Duration.ofSeconds(req.expiresInSeconds()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uploadUrl", presigned.uploadUrl());
        data.put("objectKey", presigned.objectKey());
        data.put("expiresIn", presigned.expiresIn().toString());
        return ApiResponse.ok(data, traceId());
    }

    /**
     * 预签名直传（授权签名锁定单对象 key + Content-Type + Content-Length + 时效）。
     *
     * <p>MQ 开启且文件未超内联上限时：授权校验后把字节封成密文信封投进 MQ，立即受理返回
     * （不落盘）；落盘与登记由消费端异步完成（削峰点）。</p>
     */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @PutMapping("/api/attachments/local-upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("key") String key,
            @RequestParam("contentType") String contentType,
            @RequestParam("contentLength") long contentLength,
            @RequestParam("expiresAt") long expiresAt,
            @RequestParam("signature") String signature,
            @RequestBody byte[] body) {

        DirectUploadEndpoint endpoint = endpointProvider.getIfAvailable();
        if (endpoint == null) {
            return notFoundJson();
        }
        if (!endpoint.verifyUploadSignature(key, contentType, contentLength, expiresAt, signature)) {
            return jsonStatus(HttpStatus.FORBIDDEN, Map.of("error", "上传授权无效或已过期"));
        }
        if (body.length != contentLength) {
            return jsonStatus(HttpStatus.BAD_REQUEST, Map.of("error", "文件大小与授权声明不一致"));
        }

        // 分支②：MQ 通道 —— 控制器把密文扔进 MQ（不再同步写盘）
        if (mqEnabled() && body.length <= maxInlineBytes) {
            return acceptToMq(key, contentType, body);
        }

        // 分支①③：MQ 未启用 或 文件超内联上限（回落同步写盘，行为不变）
        String sha256;
        try {
            sha256 = uploadService.store(key, body);
        } catch (IllegalArgumentException e) {
            return jsonStatus(HttpStatus.BAD_REQUEST, Map.of("error", "附件超出大小限制"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("objectKey", key);
        data.put("sha256", sha256);
        data.put("size", body.length);
        return ResponseEntity.ok(data);
    }

    /**
     * 上传完成后登记（回读对拍 SHA-256，成功返回 attachmentId）。
     *
     * <p>MQ 开启时：消费端已落盘 → 用「待登记」占位行对拍后转正；
     * 消费端尚未落盘 → HTTP 202 受理（客户端重试 register；重复 register 不产生第二行）。</p>
     */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @PostMapping("/api/attachments/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        LocalStorageAttachmentStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "当前存储实现不支持登记");
        }

        if (mqEnabled()) {
            // 待登记行（消费端已落盘、尚未正式登记）：对拍后转正
            Optional<AttachmentRow> pending = uploadService.findPending(req.objectKey());
            if (pending.isPresent()) {
                assertMatches(pending.get(), req);
                long attachmentId = uploadService.promotePending(req.objectKey(),
                        req.bizType(), req.bizId());
                return ApiResponse.ok(Map.of("attachmentId", attachmentId,
                        "provider", storage.providerName()), traceId());
            }
            // 已登记过（重复 register）：幂等返回同一行，不再插入第二行
            Optional<AttachmentRow> registered = uploadService.findRegistered(req.objectKey());
            if (registered.isPresent()) {
                assertMatches(registered.get(), req);
                return ApiResponse.ok(Map.of("attachmentId", registered.get().attachmentId(),
                        "provider", storage.providerName()), traceId());
            }
        }

        // 同步登记（既有行为：回读文件元数据对拍 SHA-256 与大小）
        ObjectMetadata head = storage.head(req.objectKey());
        if (head == null) {
            if (mqEnabled()) {
                // 消费端尚未落盘 → 受理（客户端重试 register 即可）
                markAccepted();
                return ApiResponse.ok(Map.of("accepted", true, "reason", "UPLOAD_PENDING"),
                        traceId());
            }
            throw new BusinessException(RFErrorCode.RF_VALID_001, "对象不存在，先完成上传");
        }
        if (!head.sha256().equalsIgnoreCase(req.sha256())) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "SHA-256 对拍不符，登记拒绝");
        }
        if (head.contentLength() != req.sizeBytes()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "大小与回读元数据不符，登记拒绝");
        }
        long attachmentId = attachmentRepository.insert(req.bizType(), req.bizId(), req.objectKey(),
                head.sha256(), head.contentLength(), uploadedBy(), traceId());
        return ApiResponse.ok(Map.of("attachmentId", attachmentId, "provider", storage.providerName()),
                traceId());
    }

    /** 预签名下载（授权签名绑定 key + 时效）。 */
    @GetMapping("/api/attachments/local-download")
    public ResponseEntity<byte[]> downloadSigned(
            @RequestParam("key") String key,
            @RequestParam("expiresAt") long expiresAt,
            @RequestParam("signature") String signature) {

        DirectUploadEndpoint endpoint = endpointProvider.getIfAvailable();
        if (endpoint == null) {
            return ResponseEntity.notFound().build();
        }
        if (!endpoint.verifyGetSignature(key, expiresAt, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        byte[] content = endpoint.get(key);
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filenameOf(key) + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    /** 受控下载：按 core.attachments 行回读（员工 + RELEASE_DOCUMENT_ISSUE；sha256 响应头可校验）。 */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @GetMapping("/api/attachments/{attachmentId}")
    public ResponseEntity<byte[]> download(@PathVariable long attachmentId) {
        AttachmentRepository.AttachmentRow row = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "附件不存在", Map.of("attachmentId", String.valueOf(attachmentId))));
        LocalStorageAttachmentStorage storage = storageProvider.getIfAvailable();
        byte[] content = storage == null ? null : storage.get(row.relativePath());
        if (content == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"attachment-" + row.attachmentId() + "\"")
                .header("X-Attachment-Sha256", row.sha256())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    // ---------- MQ 通道（ADR-RF-014） ----------

    /** MQ 通道是否启用：发布端就绪且开关打开。 */
    private boolean mqEnabled() {
        if (publisherProvider == null || cipherProvider == null) {
            return false;
        }
        EnvelopePublisher publisher = publisherProvider.getIfAvailable();
        EnvelopeCipher cipher = cipherProvider.getIfAvailable();
        return publisher != null && cipher != null && publisher.enabled();
    }

    /**
     * 把上传字节封成密文信封投进 MQ，立即受理（不落盘）。
     *
     * <p>broker 上只允许出现密文信封；文件字节只存在于密文内部（B-1 / ADR-RF-014 §3）。</p>
     */
    private ResponseEntity<Map<String, Object>> acceptToMq(String key, String contentType, byte[] body) {
        EnvelopePublisher publisher = publisherProvider.getIfAvailable();
        EnvelopeCipher cipher = cipherProvider.getIfAvailable();
        AttachmentEnvelopePayload payload = new AttachmentEnvelopePayload(
                key, contentType, body.length,
                Base64.getEncoder().encodeToString(body),
                uploadedBy(), traceId());
        CipherEnvelope envelope = cipher.seal(EnvelopeTopics.ATTACHMENT, key, payload.toJson(), traceId());
        publisher.publish(EnvelopeTopics.ATTACHMENT, envelope);
        log.info("附件上传已入队 envelopeId={} objectKey={} size={}",
                envelope.envelopeId(), key, body.length);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("objectKey", key);
        data.put("envelopeId", envelope.envelopeId());
        data.put("accepted", true);
        return ResponseEntity.ok(data);
    }

    /** 与既有同步登记一致的 SHA-256 / 大小对拍。 */
    private static void assertMatches(AttachmentRow row, RegisterRequest req) {
        String sha256 = row.sha256();
        if (sha256 == null || req.sha256() == null || !sha256.equalsIgnoreCase(req.sha256())) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "SHA-256 对拍不符，登记拒绝");
        }
        if (row.sizeBytes() != req.sizeBytes()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "大小与回读元数据不符，登记拒绝");
        }
    }

    /** HTTP 202（受理中）：业务体仍是统一 ApiResponse，只改 HTTP 状态码。 */
    private static void markAccepted() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servlet) {
            servlet.getResponse().setStatus(HttpStatus.ACCEPTED.value());
        }
    }

    private static String uploadedBy() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
        Object subject = attrs.getAttribute("authSubject",
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
        return subject == null || String.valueOf(subject).isBlank() ? "SYSTEM" : String.valueOf(subject);
    }

    /** 对象键末段作为文件名（键本身无 PII——UUID + 业务标识）。 */
    private static String filenameOf(String objectKey) {
        int slash = objectKey.lastIndexOf('/');
        return slash < 0 ? objectKey : objectKey.substring(slash + 1);
    }

    private static ResponseEntity<Map<String, Object>> jsonStatus(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(body);
    }

    private static ResponseEntity<Map<String, Object>> notFoundJson() {
        return jsonStatus(HttpStatus.NOT_FOUND,
                Map.of("error", "当前存储实现不支持本端点（应直传对象存储）"));
    }

    public record RegisterRequest(String objectKey, String sha256, long sizeBytes,
                                  String bizType, String bizId) {
    }

    public record PresignRequest(String objectKey, String contentType, long contentLength,
                                 long expiresInSeconds) {
    }
}
