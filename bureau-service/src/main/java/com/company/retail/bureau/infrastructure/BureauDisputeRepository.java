package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.mapper.BureauDisputeMapper;
import com.company.retail.common.persistence.RowValues;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 征信异议工单 MyBatis 仓储（integration.bureau_disputes；SLA 30 日 due_at，C-05）。
 *
 * <p>本实现与端口 {@code com.company.retail.bureau.application.BureauDisputeRepository} 同名，
 * 故 implements 子句用全限定名；端口内的 {@code DisputeRow} 记录由本类继承使用，
 * 对外方法签名与语义一字未改。</p>
 */
public class BureauDisputeRepository
        implements com.company.retail.bureau.application.BureauDisputeRepository {

    private final BureauDisputeMapper mapper;

    public BureauDisputeRepository(BureauDisputeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insert(String customerRef, String bureau, String type,
                       OffsetDateTime dueAt, String traceId) {
        BureauDisputeMapper.DisputeInsert param =
                new BureauDisputeMapper.DisputeInsert(customerRef, bureau, type, dueAt, traceId);
        mapper.insert(param);
        Long disputeId = param.getDisputeId();
        if (disputeId == null) {
            // 驱动未回填主键 → 与原 RETURNING 返回空集同义（插入未生效）
            throw new org.springframework.dao.EmptyResultDataAccessException(1);
        }
        return disputeId;
    }

    @Override
    public Optional<DisputeRow> findById(long disputeId) {
        List<Map<String, Object>> rows = mapper.findById(disputeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public boolean markResolved(long disputeId, String resolutionRef, String traceId) {
        return mapper.markResolved(disputeId, resolutionRef, traceId) > 0;
    }

    private static DisputeRow toRow(Map<String, Object> row) {
        return new DisputeRow(RowValues.asLong(row, "dispute_id"),
                RowValues.asString(row, "customer_ref"),
                RowValues.asString(row, "bureau"),
                RowValues.asString(row, "type"),
                asOffsetDateTime(row, "due_at"),
                RowValues.asString(row, "status"));
    }

    /**
     * TIMESTAMPTZ → OffsetDateTime（口径对齐原 {@code ResultSet.getObject(col, OffsetDateTime.class)}：
     * 同一时刻，按 JVM 默认时区渲染偏移量；NULL → null）。
     */
    private static OffsetDateTime asOffsetDateTime(Map<String, Object> row, String col) {
        Instant instant = RowValues.asInstant(row, col);
        return instant == null ? null : instant.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
