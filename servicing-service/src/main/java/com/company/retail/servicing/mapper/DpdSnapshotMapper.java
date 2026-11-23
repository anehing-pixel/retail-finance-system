package com.company.retail.servicing.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DPD 快照 MyBatis Mapper（表 core.dpd_snapshots；uk_dpd_loan_date 幂等）。
 */
public interface DpdSnapshotMapper {

    /** 当日快照（ON CONFLICT (loan_id, biz_date) DO NOTHING）；当日已有返回 0 行。 */
    @Insert("INSERT INTO core.dpd_snapshots (loan_id, biz_date, dpd_days, stage, day_basis, trace_id) "
            + "VALUES (#{loanId}, #{bizDate}, #{dpdDays}, #{stage}, 'NATURAL', #{traceId}) "
            + "ON CONFLICT (loan_id, biz_date) DO NOTHING")
    int insert(@Param("loanId") long loanId,
               @Param("bizDate") LocalDate bizDate,
               @Param("dpdDays") int dpdDays,
               @Param("stage") String stage,
               @Param("traceId") String traceId);

    @Select("SELECT snapshot_id, loan_id, biz_date, dpd_days, stage, day_basis "
            + "FROM core.dpd_snapshots WHERE loan_id = #{loanId} AND biz_date < #{bizDate} "
            + "ORDER BY biz_date DESC LIMIT 1")
    List<Map<String, Object>> findLatestBefore(@Param("loanId") long loanId,
                                                @Param("bizDate") LocalDate bizDate);

    @Select("SELECT snapshot_id, loan_id, biz_date, dpd_days, stage, day_basis "
            + "FROM core.dpd_snapshots WHERE loan_id = #{loanId} AND biz_date = #{bizDate}")
    List<Map<String, Object>> find(@Param("loanId") long loanId,
                                    @Param("bizDate") LocalDate bizDate);

    @Select("SELECT snapshot_id, loan_id, biz_date, dpd_days, stage, day_basis "
            + "FROM core.dpd_snapshots WHERE loan_id = #{loanId} ORDER BY biz_date DESC LIMIT 200")
    List<Map<String, Object>> listByLoan(@Param("loanId") long loanId);
}
