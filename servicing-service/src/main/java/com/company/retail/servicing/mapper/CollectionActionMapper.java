package com.company.retail.servicing.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 催收动作留痕 MyBatis Mapper（表 core.collection_actions；append-only，无 update/delete）。
 */
public interface CollectionActionMapper {

    @Insert("INSERT INTO core.collection_actions "
            + "(case_id, action_type, outcome, note, actor, actor_subject, trace_id) "
            + "VALUES (#{caseId}, #{actionType}, #{outcome}, #{note}, #{actor}, #{actorSubject}, #{traceId})")
    int insert(@Param("caseId") long caseId,
               @Param("actionType") String actionType,
               @Param("outcome") String outcome,
               @Param("note") String note,
               @Param("actor") String actor,
               @Param("actorSubject") String actorSubject,
               @Param("traceId") String traceId);

    @Select("SELECT action_id, case_id, action_type, outcome, note, actor, actor_subject, trace_id "
            + "FROM core.collection_actions WHERE case_id = #{caseId} ORDER BY action_id")
    List<Map<String, Object>> listByCase(@Param("caseId") long caseId);
}
