package com.company.retail.titlerelease.web;

import com.company.platform.exception.BusinessException;
import com.company.platform.response.ApiResponse;
import com.company.platform.security.RequirePermission;
import com.company.platform.security.RequireSubject;
import com.company.platform.storage.DirectUploadEndpoint;
import com.company.platform.storage.AttachmentStorage.ObjectMetadata;
import com.company.retail.common.config.RfSecurityConfig;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.domain.model.Permission;
import com.company.retail.titlerelease.persistence.AttachmentRepository;
import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
import org.springframework.beans.factory.ObjectProvider;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 附件端点（ADR-RF-008：本地文件系统存储；W9-C 接线）。
 *
 * <p>平台 {@link DirectUploadEndpoint} 机制下，本地实现由应用提供上传/下载端点
 * （本地磁盘没有对象存储直传，S3 实现无需本端点）。用 {@link ObjectProvider} 注入端口，
 * web 层不依赖具体实现类。</p>
 *
 * <ul>
 *   <li>POST /api/attachments/presign —— 签发上传授权（锁定 key/类型/长度/时效）；</li>
 *   <li>PUT  /api/attachments/local-upload —— 预签名直传（员工 + HMAC 授权绑定 key/长度/时效）；</li>
 *   <li>POST /api/attachments/register —— 回读元数据（SHA-256 对拍）登记 core.attachments；</li>
 *   <li>GET  /api/attachments/local-download —— 预签名下载（员工 + HMAC 授权）；</li>
 *   <li>GET  /api/attachments/{id} —— 受控下载（员工权限 + RELEASE_DOCUMENT_ISSUE，
 *       按 core.attachments 行回读；响应不回显任何客户 PII，文件名只用对象键）。</li>
 * </ul>
 */
@RestController
@RequireSubject(com.company.platform.security.SubjectType.EMPLOYEE)
public class AttachmentController {

    private final ObjectProvider<DirectUploadEndpoint> endpointProvider;
    private final ObjectProvider<LocalStorageAttachmentStorage> storageProvider;
    private final AttachmentRepository attachmentRepository;

    public AttachmentController(ObjectProvider<DirectUploadEndpoint> endpointProvider,
                                ObjectProvider<LocalStorageAttachmentStorage> storageProvider,
                                AttachmentRepository attachmentRepository) {
        this.endpointProvider = endpointProvider;
        this.storageProvider = storageProvider;
        this.attachmentRepository = attachmentRepository;
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

    /** 预签名直传（授权签名锁定单对象 key + Content-Type + Content-Length + 时效）。 */
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
        String sha256;
        try {
            sha256 = endpoint.put(key, body);
        } catch (IllegalArgumentException e) {
            return jsonStatus(HttpStatus.BAD_REQUEST, Map.of("error", "附件超出大小限制"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("objectKey", key);
        data.put("sha256", sha256);
        data.put("size", body.length);
        return ResponseEntity.ok(data);
    }

    /** 上传完成后登记（回读对拍 SHA-256，成功返回 attachmentId）。 */
    @RequirePermission(Permission.RELEASE_DOCUMENT_ISSUE)
    @PostMapping("/api/attachments/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest req) {
        LocalStorageAttachmentStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "当前存储实现不支持登记");
        }
        ObjectMetadata head = storage.head(req.objectKey());
        if (head == null) {
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
