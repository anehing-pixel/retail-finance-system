package com.company.retail.titlerelease.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.titlerelease.application.TitleRepositoryPort;
import com.company.retail.titlerelease.application.TitleRepositoryPort.TitleRow;
import com.company.retail.titlerelease.mapper.TitleMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code core.title_retentions} 持久化（V8 表；权威写者 BC-8 title-release-service，P-5）。
 * uk_title_loan：一债权一条保留记录。DOCUMENTS_ISSUED 不写本表状态（两态分离）。
 *
 * <p>插入用 {@code ON CONFLICT (loan_id) DO NOTHING} + 自增主键回填：并发写入
 * （操作员主动登记 vs LOAN_SETTLED 兜底建单）时<b>不抛唯一键异常</b>——冲突不插入、
 * 主键不回填（返回 0），由调用方幂等处理。顺带避免 PostgreSQL「唯一键错误即整笔事务 aborted」的坑。</p>
 */
@Repository
public class TitleRepository implements TitleRepositoryPort {

    private final TitleMapper mapper;

    public TitleRepository(TitleMapper mapper) {
        this.mapper = mapper;
    }

    /** 插入保留记录；uk_title_loan 冲突（并发被抢先）返回 0，不抛异常。 */
    @Override
    public long insert(long loanId, String vin, String status, String registrationMode,
                       Timestamp registeredAt, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("loanId", loanId);
        params.put("vin", vin);
        params.put("status", status);
        params.put("registrationMode", registrationMode);
        params.put("registeredAt", registeredAt);
        params.put("traceId", traceId);
        mapper.insert(params);
        Object id = params.get("titleId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    @Override
    public Optional<TitleRow> findByLoanId(long loanId) {
        List<Map<String, Object>> rows = mapper.findByLoanId(loanId);
        return rows.stream().findFirst().map(TitleRepository::toRow);
    }

    @Override
    public Optional<TitleRow> findById(long titleId) {
        List<Map<String, Object>> rows = mapper.findById(titleId);
        return rows.stream().findFirst().map(TitleRepository::toRow);
    }

    /**
     * 登记订正：挡板占位行 → 正式登记。三重并发守卫（title_id + version + 占位 VIN）。
     *
     * <p><b>不写 status</b>：同态订正，状态变更只走聚合状态机（R04 §3.5）；
     * {@code AND vin = 占位 VIN} 保证只作用于占位行。</p>
     */
    @Override
    public int correctPlaceholderRegistration(long titleId, String placeholderVin, String vin,
                                              String registrationMode, Timestamp registeredAt, int version) {
        return mapper.correctPlaceholderRegistration(titleId, placeholderVin, vin, registrationMode,
                registeredAt, version);
    }

    /** 状态迁移（RELEASE_REGISTERED / CLOSED；乐观锁兜底）。 */
    @Override
    public int updateStatus(long titleId, String status, Timestamp releasedAt, int version) {
        return mapper.updateStatus(titleId, status, releasedAt, version);
    }

    private static TitleRow toRow(Map<String, Object> row) {
        return new TitleRow(
                RowValues.asLong(row, "title_id"),
                RowValues.asLong(row, "loan_id"),
                RowValues.asString(row, "vin"),
                RowValues.asString(row, "status"),
                RowValues.asString(row, "registration_mode"),
                RowValues.asTimestamp(row, "registered_at"),
                RowValues.asTimestamp(row, "released_at"),
                RowValues.asInt(row, "version"));
    }
}
