package com.company.retail.titlerelease.application;

import com.company.platform.exception.BusinessException;
import com.company.platform.storage.DirectUploadEndpoint;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.titlerelease.persistence.AttachmentRepository;
import com.company.retail.titlerelease.persistence.AttachmentRepository.AttachmentRow;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

/**
 * 附件落盘与登记编排（同步直传与 MQ 消费端共用同一实现，ADR-RF-014）。
 *
 * <p><b>为什么要抽这一层</b>：「写盘 + 登记待登记行」「待登记行转正」各只有一份实现——
 * 同步直传（MQ 未启用 / 超限回落）与 MQ 消费端都调用本类，
 * 杜绝同一语义两套业务逻辑。</p>
 *
 * <p><b>待登记行</b>：MQ 消费端落盘后先登记占位行（{@code biz_type='PENDING_UPLOAD'}、
 * {@code biz_id=objectKey}），正式登记由 register 端点「转正」（改 biz_type / biz_id，
 * attachment_id 不变）。占位行复用现有 {@code core.attachments} 表，<b>不改表结构</b>；
 * 插入幂等（同一 objectKey 不重复插入，用 INSERT ... WHERE NOT EXISTS，不用「捕获唯一键异常再重读」——
 * PostgreSQL 事务内唯一键错误会把整笔事务置为 aborted）。</p>
 *
 * <p><b>装配</b>：本类无状态，由使用方（控制器 / MQ 消费者）以显式依赖装配，
 * 同一实现、多实例无状态，不额外增加装配口径。</p>
 */
public class AttachmentUploadService {

    private final ObjectProvider<DirectUploadEndpoint> endpointProvider;
    private final AttachmentRepository repository;

    public AttachmentUploadService(ObjectProvider<DirectUploadEndpoint> endpointProvider,
                                   AttachmentRepository repository) {
        this.endpointProvider = endpointProvider;
        this.repository = repository;
    }

    /**
     * 落盘（唯一写盘实现）：返回内容 SHA-256。
     *
     * <p>超限由存储实现抛 {@link IllegalArgumentException}，由调用方翻译为 400。</p>
     */
    public String store(String objectKey, byte[] bytes) {
        DirectUploadEndpoint endpoint = endpointProvider.getIfAvailable();
        if (endpoint == null) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "当前存储实现不支持直传");
        }
        return endpoint.put(objectKey, bytes);
    }

    /**
     * 写盘 + 登记待登记行（幂等）。
     *
     * <p>MQ 消费端（异步落盘）与超限回落（同步落盘）共用本方法：
     * 落盘一次、占位行一条，register 端点据此转正。</p>
     */
    public void storeAndRegisterPending(String objectKey, byte[] bytes, String uploadedBy, String traceId) {
        String sha256 = store(objectKey, bytes);
        repository.insertPendingIfAbsent(objectKey, sha256, bytes.length, uploadedBy, traceId);
    }

    /** 待登记行（消费者已落盘、尚未正式登记）。 */
    public Optional<AttachmentRow> findPending(String objectKey) {
        return repository.findPending(objectKey);
    }

    /** 已登记行（按相对路径 = objectKey）：register 幂等，避免重复调用产生第二行。 */
    public Optional<AttachmentRow> findRegistered(String relativePath) {
        return repository.findRegisteredByRelativePath(relativePath);
    }

    /**
     * 待登记行转正：把 {@code biz_type='PENDING_UPLOAD'} 的占位行改成正式 biz_type / biz_id。
     *
     * <p>返回正式行的 attachment_id（转正不改主键）。并发转正时后到者落到
     * 「按相对路径回查」分支，返回同一 attachment_id（幂等）。</p>
     */
    public long promotePending(String objectKey, String bizType, String bizId) {
        AttachmentRow pending = repository.findPending(objectKey)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001, "待登记附件不存在"));
        if (repository.promotePending(objectKey, bizType, bizId)) {
            return pending.attachmentId();
        }
        return repository.findRegisteredByRelativePath(objectKey)
                .map(AttachmentRow::attachmentId)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001, "待登记附件转正失败"));
    }
}
