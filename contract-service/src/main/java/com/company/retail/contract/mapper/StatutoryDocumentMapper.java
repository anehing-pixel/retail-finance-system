package com.company.retail.contract.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 法定书面文件 Mapper（MyBatis 注解 SQL；表 {@code core.statutory_documents}）。
 *
 * <p>交付留痕 append-only，不物理删除。主键由驱动回填到入参 Map 的 {@code docId} 键
 * （原实现用 GeneratedKeyHolder 取值，语义等价）。</p>
 */
public interface StatutoryDocumentMapper {

    /** 追加交付留痕行。 */
    @Insert("INSERT INTO core.statutory_documents (contract_id, doc_type, template_version, "
            + "delivered_at, delivery_method, attachment_id, trace_id) "
            + "VALUES (#{contractId}, #{docType}, #{templateVersion}, "
            + "#{deliveredAt,jdbcType=TIMESTAMP}, #{deliveryMethod,jdbcType=VARCHAR}, "
            + "#{attachmentId,jdbcType=BIGINT}, #{traceId,jdbcType=VARCHAR})")
    @Options(useGeneratedKeys = true, keyProperty = "docId", keyColumn = "doc_id")
    int insertDelivery(Map<String, Object> params);

    /** 按合同号查全部交付留痕（按 doc_id 升序）。 */
    @Select("SELECT doc_id, contract_id, doc_type, template_version, delivered_at, delivery_method, "
            + "attachment_id, acknowledged_at FROM core.statutory_documents "
            + "WHERE contract_id = #{contractId} ORDER BY doc_id")
    List<Map<String, Object>> findByContract(@Param("contractId") long contractId);
}
