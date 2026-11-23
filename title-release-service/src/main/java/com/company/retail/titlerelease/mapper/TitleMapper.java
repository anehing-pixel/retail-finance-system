package com.company.retail.titlerelease.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 所有权保留登记 MyBatis Mapper（表 core.title_retentions，V8；权威写者 BC-8 title-release-service）。
 *
 * <p>uk_title_loan：一债权一条保留记录。DOCUMENTS_ISSUED 不写本表状态（两态分离）。</p>
 *
 * <p>插入用 {@code ON CONFLICT (loan_id) DO NOTHING} + 自增主键回填：并发写入
 * （操作员主动登记 vs LOAN_SETTLED 兜底建单）时<b>不抛唯一键异常</b>——冲突不插入、
 * 主键不回填（返回 0），由调用方幂等处理。顺带避免 PostgreSQL「唯一键错误即整笔事务 aborted」的坑。</p>
 */
public interface TitleMapper {

    @Insert("INSERT INTO core.title_retentions (loan_id, vin, status, registration_mode, "
            + "registered_at, trace_id) VALUES (#{loanId}, #{vin}, #{status}, #{registrationMode}, "
            + "#{registeredAt}, #{traceId}) ON CONFLICT (loan_id) DO NOTHING")
    @Options(useGeneratedKeys = true, keyProperty = "titleId", keyColumn = "title_id")
    int insert(Map<String, Object> params);

    @Select("SELECT title_id, loan_id, vin, status, registration_mode, registered_at, released_at, version "
            + "FROM core.title_retentions WHERE loan_id = #{loanId}")
    List<Map<String, Object>> findByLoanId(@Param("loanId") long loanId);

    @Select("SELECT title_id, loan_id, vin, status, registration_mode, registered_at, released_at, version "
            + "FROM core.title_retentions WHERE title_id = #{titleId}")
    List<Map<String, Object>> findById(@Param("titleId") long titleId);

    /**
     * 登记订正：挡板占位行 → 正式登记。三重并发守卫（title_id + version + 占位 VIN）。
     *
     * <p><b>不写 status</b>：同态订正，状态变更只走聚合状态机（R04 §3.5）；
     * {@code AND vin = 占位 VIN} 保证只作用于占位行。</p>
     */
    @Update("UPDATE core.title_retentions SET vin = #{vin}, registration_mode = #{registrationMode}, "
            + "registered_at = #{registeredAt}, version = version + 1, updated_at = now() "
            + "WHERE title_id = #{titleId} AND version = #{version} AND vin = #{placeholderVin}")
    int correctPlaceholderRegistration(@Param("titleId") long titleId,
                                       @Param("placeholderVin") String placeholderVin,
                                       @Param("vin") String vin,
                                       @Param("registrationMode") String registrationMode,
                                       @Param("registeredAt") Timestamp registeredAt,
                                       @Param("version") int version);

    /** 状态迁移（RELEASE_REGISTERED / CLOSED；乐观锁兜底）。 */
    @Update("UPDATE core.title_retentions SET status = #{status}, "
            + "released_at = COALESCE(#{releasedAt}, released_at), "
            + "version = version + 1, updated_at = now() "
            + "WHERE title_id = #{titleId} AND version = #{version}")
    int updateStatus(@Param("titleId") long titleId, @Param("status") String status,
                     @Param("releasedAt") Timestamp releasedAt, @Param("version") int version);
}
