package com.company.retail.bureau.infrastructure;

import com.company.retail.bureau.domain.InquiryStatus;
import com.company.retail.bureau.mapper.CreditInquiryMapper;
import com.company.retail.common.persistence.RowValues;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 征信查询留痕 MyBatis 仓储（core.credit_inquiries；consent_id NOT NULL 由 DB 兜底，INV-5）。
 *
 * <p>本实现与端口 {@code com.company.retail.bureau.application.CreditInquiryRepository} 同名，
 * 故 implements 子句用全限定名；端口内的 {@code InquiryRow} 记录由本类继承使用，
 * 对外方法签名与语义一字未改。</p>
 */
public class CreditInquiryRepository
        implements com.company.retail.bureau.application.CreditInquiryRepository {

    private final CreditInquiryMapper mapper;

    public CreditInquiryRepository(CreditInquiryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insert(long caseId, long consentId, String bureau, String purpose, String traceId) {
        CreditInquiryMapper.InquiryInsert param =
                new CreditInquiryMapper.InquiryInsert(caseId, consentId, bureau, purpose, traceId);
        mapper.insert(param);
        Long inquiryId = param.getInquiryId();
        if (inquiryId == null) {
            // 驱动未回填主键 → 与原 RETURNING 返回空集同义（插入未生效）
            throw new EmptyResultDataAccessException(1);
        }
        return inquiryId;
    }

    @Override
    public boolean markFinished(long inquiryId, InquiryStatus status, String resultSnapshotRef,
                                long costAmount, String traceId) {
        return mapper.markFinished(inquiryId, status.name(), resultSnapshotRef, costAmount, traceId) > 0;
    }

    @Override
    public Optional<InquiryRow> findById(long inquiryId) {
        List<Map<String, Object>> rows = mapper.findById(inquiryId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(toRow(rows.get(0)));
    }

    @Override
    public int countFailed(long caseId, String bureau) {
        Integer count = mapper.countFailed(caseId, bureau);
        return count == null ? 0 : count;
    }

    private static InquiryRow toRow(Map<String, Object> row) {
        return new InquiryRow(RowValues.asLong(row, "inquiry_id"),
                RowValues.asLong(row, "case_id"),
                RowValues.asLong(row, "consent_id"),
                RowValues.asString(row, "bureau"),
                RowValues.asString(row, "purpose"),
                RowValues.asString(row, "status"));
    }
}
