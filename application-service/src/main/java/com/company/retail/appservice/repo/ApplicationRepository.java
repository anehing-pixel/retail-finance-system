package com.company.retail.appservice.repo;

import com.company.retail.appservice.domain.Application;
import com.company.retail.appservice.domain.ApplicationStatus;
import com.company.retail.appservice.mapper.ApplicationMapper;
import com.company.retail.common.persistence.RowValues;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * core.applications + core.application_items 的 MyBatis 仓储（R06 §4.2）。
 *
 * <p>SQL 语义、方法签名、事务边界与改造前的 JDBC 版一致：受理写入与事件发布仍在
 * 同一个本地事务里（事务边界在应用层用例，本类不加 {@code @Transactional}）。</p>
 */
@Repository
public class ApplicationRepository {

    private final ApplicationMapper mapper;

    public ApplicationRepository(ApplicationMapper mapper) {
        this.mapper = mapper;
    }

    /** 同客户同 VIN 在途（非终态）申请的最近提交时刻（去重窗口判定输入）。 */
    public Optional<Instant> findPriorDuplicateSubmittedAt(long customerId, String vin) {
        List<Map<String, Object>> rows = mapper.findPriorDuplicateSubmittedAt(customerId, vin);
        Instant at = rows.isEmpty() ? null : RowValues.asInstant(rows.get(0), "submitted_at");
        return at == null ? Optional.empty() : Optional.of(at);
    }

    /** 插入申请（SUBMITTED 起点）+ 车辆项，返回 applicationId。 */
    public long insert(Application agg, String applicationNo, Application.Item item,
                       String idempotencyKey, String requestFingerprint, String traceId) {
        Map<String, Object> params = new HashMap<>();
        params.put("applicationNo", applicationNo);
        params.put("customerId", agg.getCustomerId());
        params.put("dealerPartnerId", agg.getDealerPartnerId());
        params.put("transactionScheme", agg.getTransactionScheme());
        params.put("submittedAt", agg.getSubmittedAt());
        params.put("channel", agg.getChannel());
        params.put("idempotencyKey", idempotencyKey);
        params.put("requestFingerprint", requestFingerprint);
        params.put("consentRef", agg.getConsentId());
        params.put("traceId", traceId);
        mapper.insertApplication(params);
        // 生成键回填：冲突/未插入时为 null（等价原 RETURNING 返回空集）→ 按「未插入」处理
        Object generated = params.get("applicationId");
        if (generated == null) {
            throw new IllegalStateException("申请插入未回填生成键 application_id");
        }
        long applicationId = ((Number) generated).longValue();
        mapper.insertItem(applicationId, item.vin(), item.model(), item.cashPrice(),
                item.accessoriesAmount(), traceId);
        return applicationId;
    }

    public Optional<Application> findById(long applicationId) {
        List<Map<String, Object>> rows = mapper.findById(applicationId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toAggregate(rows.get(0)));
    }

    public Optional<Application> findByIdempotencyKey(String idempotencyKey) {
        List<Map<String, Object>> rows = mapper.findByIdempotencyKey(idempotencyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toAggregate(rows.get(0)));
    }

    /** 状态回写（目标状态由聚合方法产出）。 */
    public void updateStatus(long applicationId, ApplicationStatus status) {
        mapper.updateStatus(applicationId, status.name());
    }

    private Application toAggregate(Map<String, Object> row) {
        Instant submitted = RowValues.asInstant(row, "submitted_at");
        // consent_ref：申请时声明的同意引用（挡板口径），无对应列时为空
        String consentRef = RowValues.asString(row, "consent_ref");
        return Application.restore(
                RowValues.asLong(row, "application_id"),
                RowValues.asString(row, "application_no"),
                RowValues.asLong(row, "customer_id"),
                RowValues.asLongOrNull(row, "dealer_partner_id"),
                ApplicationStatus.valueOf(RowValues.asString(row, "status")),
                RowValues.asString(row, "transaction_scheme"),
                RowValues.asString(row, "channel"),
                submitted == null ? Instant.now() : submitted,
                consentRef == null ? null : Long.valueOf(consentRef));
    }
}
