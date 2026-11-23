package com.company.retail.bureau.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 机构报文留痕 Mapper（integration.bureau_message_logs，V11 增量表）。
 *
 * <p><b>append-only</b>：本 Mapper 只提供 INSERT 与统计查询，不提供 UPDATE/DELETE——
 * 报文留痕不可改写、不可删除（R05 X-11 / ADR-RF-005 §2）。</p>
 */
public interface BureauMessageLogMapper {

    /** 追加一条报文留痕（direction: OUTBOUND 查询/报送请求，INBOUND 机构应答）。 */
    @Insert("INSERT INTO integration.bureau_message_logs "
            + "(direction, bureau, biz_ref, message_text, trace_id) "
            + "VALUES (#{direction}, #{bureau}, #{bizRef}, #{messageText}, #{traceId,jdbcType=VARCHAR})")
    int append(@Param("direction") String direction,
               @Param("bureau") String bureau,
               @Param("bizRef") String bizRef,
               @Param("messageText") String messageText,
               @Param("traceId") String traceId);

    /** 按业务引用统计留痕条数（留痕存在性断言用）。 */
    @Select("SELECT COUNT(*) FROM integration.bureau_message_logs WHERE biz_ref = #{bizRef}")
    Long countByBizRef(@Param("bizRef") String bizRef);
}
