package com.company.retail.titlerelease.storage;

import com.company.platform.storage.AttachmentStorage;
import com.company.platform.storage.DirectUploadEndpoint;
import com.company.retail.common.security.SecretPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地文件系统附件存储（ADR-RF-008 定源：只用本地实现，不引入 S3/MinIO）。
 *
 * <p>实现平台 {@link AttachmentStorage} + {@link DirectUploadEndpoint} 双端口：
 * 本地磁盘没有真正的"对象存储直传"，上传/下载必须由应用端点承载
 * （web 层入口见 {@code AttachmentController}）。</p>
 *
 * <ul>
 *   <li>落盘 {@code retail.attachments.base-dir}（默认 {@code data/attachments/}），
 *       DB 只存相对路径（core.attachments.relative_path，V1 口径）；</li>
 *   <li>SHA-256 校验：put 返回内容哈希，登记时入库（core.attachments.sha256）；</li>
 *   <li>大小限制 {@code retail.attachments.max-size-bytes}（默认 10MB），超限拒绝；</li>
 *   <li>预签名 = HMAC-SHA256 绑定 objectKey + contentType + contentLength（或 GET）+ 过期时刻，
 *       授权 A 不能用来传 B；</li>
 *   <li>防目录穿越：解析后必须仍在根目录内；objectKey 用 UUID，不用原始文件名（PII）。</li>
 * </ul>
 */
@Component
public class LocalStorageAttachmentStorage implements AttachmentStorage, DirectUploadEndpoint {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageAttachmentStorage.class);

    /** 默认大小上限：10MB。 */
    public static final long DEFAULT_MAX_SIZE_BYTES = 10L * 1024 * 1024;

    private final Path root;
    private final String tokenSecret;
    private final long maxSizeBytes;

    public LocalStorageAttachmentStorage(
            @Value("${retail.attachments.base-dir:data/attachments}") String baseDir,
            @Value("${retail.attachments.token-secret}") String tokenSecret,
            @Value("${retail.attachments.max-size-bytes:" + DEFAULT_MAX_SIZE_BYTES + "}") long maxSizeBytes,
            Environment environment) {
        // R11 P0-8：无默认值；缺失 / 弱值 fail-fast（dev profile 例外仅限本地）
        this.tokenSecret = SecretPolicy.requireStrong(
                "retail.attachments.token-secret（RETAIL_ATTACHMENTS_TOKEN_SECRET）",
                tokenSecret, SecretPolicy.devMode(environment.getActiveProfiles()));
        this.root = Paths.get(baseDir).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建附件根目录：" + this.root, e);
        }
        log.info("本地附件存储已启用 root={} maxSizeBytes={}（ADR-RF-008：本地实现）", root, maxSizeBytes);
    }

    public long maxSizeBytes() {
        return maxSizeBytes;
    }

    // ---------- AttachmentStorage 端口 ----------

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long contentLength,
                                         Duration expiresIn) {
        long expiresAt = System.currentTimeMillis() + expiresIn.toMillis();
        String signature = sign(objectKey + "|" + contentType + "|" + contentLength + "|" + expiresAt);
        String url = "/api/attachments/local-upload?key=" + urlEncode(objectKey)
                + "&contentType=" + urlEncode(contentType)
                + "&contentLength=" + contentLength
                + "&expiresAt=" + expiresAt
                + "&signature=" + signature;
        return new PresignedUpload(url, objectKey, expiresIn, contentType, contentLength);
    }

    @Override
    public ObjectMetadata head(String objectKey) {
        byte[] bytes = get(objectKey);
        if (bytes == null) {
            return null;
        }
        return new ObjectMetadata(objectKey, bytes.length, "application/octet-stream",
                HexFormat.of().formatHex(sha256Bytes(bytes)), sha256Hex(bytes));
    }

    @Override
    public String presignGet(String objectKey, Duration expiresIn) {
        if (get(objectKey) == null) {
            return null;
        }
        long expiresAt = System.currentTimeMillis() + expiresIn.toMillis();
        String signature = sign(objectKey + "|GET|" + expiresAt);
        return "/api/attachments/local-download?key=" + urlEncode(objectKey)
                + "&expiresAt=" + expiresAt + "&signature=" + signature;
    }

    @Override
    public String providerName() {
        return "local";
    }

    // ---------- DirectUploadEndpoint 端口 ----------

    @Override
    public String uploadEndpointPath() {
        return "/api/attachments/local-upload";
    }

    @Override
    public String downloadEndpointPath() {
        return "/api/attachments/local-download";
    }

    @Override
    public boolean verifyUploadSignature(String objectKey, String contentType, long contentLength,
                                         long expiresAt, String signature) {
        if (expiresAt < System.currentTimeMillis() || contentLength > maxSizeBytes) {
            return false;
        }
        return constEq(sign(objectKey + "|" + contentType + "|" + contentLength + "|" + expiresAt),
                signature);
    }

    @Override
    public boolean verifyGetSignature(String objectKey, long expiresAt, String signature) {
        if (expiresAt < System.currentTimeMillis()) {
            return false;
        }
        return constEq(sign(objectKey + "|GET|" + expiresAt), signature);
    }

    /** 写入对象（超限拒绝；返回 SHA-256 十六进制）。 */
    @Override
    public String put(String objectKey, byte[] bytes) {
        if (bytes == null || bytes.length > maxSizeBytes) {
            throw new IllegalArgumentException("附件超出大小限制 maxSizeBytes=" + maxSizeBytes);
        }
        try {
            Path target = resolve(objectKey);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return sha256Hex(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("附件写入失败：" + objectKey, e);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try {
            Path target = resolve(objectKey);
            return Files.exists(target) ? Files.readAllBytes(target) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------- 对象键 ----------

    /** 对象键：bizType/yyyyMM/uuid-bizId-docType 后缀（不用原始文件名，PII 不进键与目录）。 */
    public static String buildObjectKey(String bizType, String bizId, String docType, String extension) {
        String ym = LocalDate.now().toString().substring(0, 7).replace("-", "/");
        String doc = docType == null ? "" : "-" + docType.toLowerCase(Locale.ROOT);
        return bizType.toLowerCase(Locale.ROOT) + "/" + ym + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "-" + bizId + doc
                + (extension == null || extension.isBlank() ? "" : extension);
    }

    // ---------- 内部 ----------

    private Path resolve(String objectKey) {
        Path p = root.resolve(objectKey).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("非法的对象键（目录穿越）：" + objectKey);
        }
        return p;
    }

    private String sign(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    tokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("附件授权签名计算失败", e);
        }
    }

    /** 定长比较，避免时序侧信道。 */
    private static boolean constEq(String expected, String actual) {
        if (actual == null || expected.length() != actual.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length(); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }

    private static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Bytes(bytes));
    }

    private static byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
