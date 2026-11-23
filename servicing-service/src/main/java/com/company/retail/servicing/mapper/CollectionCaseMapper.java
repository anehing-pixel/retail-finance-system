package com.company.retail.servicing.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 催收案件 MyBatis Mapper（表 core.collection_cases）。
 *
 * <p>「一债权一在途催收案」由 uk_coll_loan_open 部分唯一索引兜底
 * （PG 表内 UNIQUE 不支持 WHERE，V9 已建表外索引，floorplan V19 教训）。</p>
 */
public interface CollectionCaseMapper {

    /**
     * 新开案件。
     *
     * <p>用 {@code ON CONFLICT (loan_id) WHERE status <> 'CLOSED' DO NOTHING} 而非
     * 「捕获 DuplicateKeyException」：PostgreSQL 事务内一旦触发唯一键错误，整笔事务即被置为
     * aborted。冲突时不插入，返回 0 行（该债权已有在途案件）。</p>
     */
    @Insert("INSERT INTO core.collection_cases (loan_id, dpd_stage, status, trace_id) "
            + "VALUES (#{loanId}, #{dpdStage}, 'OPEN', #{traceId}) "
            + "ON CONFLICT (loan_id) WHERE status <> 'CLOSED' DO NOTHING")
    int insert(@Param("loanId") long loanId,
               @Param("dpdStage") String dpdStage,
               @Param("traceId") String traceId);

    @Select("SELECT case_id, loan_id, dpd_stage, status, assigned_to, version "
            + "FROM core.collection_cases WHERE case_id = #{caseId}")
    List<Map<String, Object>> findById(@Param("caseId") long caseId);

    @Select("SELECT case_id, loan_id, dpd_stage, status, assigned_to, version "
            + "FROM core.collection_cases WHERE loan_id = #{loanId} AND status <> 'CLOSED' "
            + "ORDER BY case_id DESC LIMIT 1")
    List<Map<String, Object>> findOpenByLoan(@Param("loanId") long loanId);

    @Update("UPDATE core.collection_cases SET status = #{status}, assigned_to = #{assignedTo}, "
            + "closed_at = CASE WHEN #{closed} THEN now() ELSE closed_at END, "
            + "trace_id = #{traceId}, version = version + 1, updated_at = now() "
            + "WHERE case_id = #{caseId} AND version = #{expectedVersion}")
    int updateStatus(@Param("caseId") long caseId,
                     @Param("status") String status,
                     @Param("assignedTo") String assignedTo,
                     @Param("closed") boolean closed,
                     @Param("expectedVersion") long expectedVersion,
                     @Param("traceId") String traceId);

    @Select("<script>"
            + "SELECT case_id, loan_id, dpd_stage, status, assigned_to, version "
            + "FROM core.collection_cases"
            + "<if test=\"statusOrNull != null and statusOrNull.trim().length() > 0\">"
            + " WHERE status = #{statusOrNull}"
            + "</if>"
            + " ORDER BY case_id DESC LIMIT 200"
            + "</script>")
    List<Map<String, Object>> list(@Param("statusOrNull") String statusOrNull);
}
