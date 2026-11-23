package com.company.retail.core.persistence;

import com.company.retail.common.persistence.RowValues;
import com.company.retail.core.domain.ProductProfile;
import com.company.retail.core.mapper.FinancialProductMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code core.financial_products} 持久化（V18）。
 *
 * <p>只读多（放款计划生成）、只写少（配置改版），读侧走两级缓存。</p>
 */
@Repository
public class FinancialProductRepository {

    /** 档位行快照。 */
    public record ProductRow(long productId, String productCode, int productVersion, String productName,
                              String scheme, int termCount, int rateBp, Integer bonusMonth,
                              Integer waiverCapBp, LocalDate effectiveFrom, LocalDate effectiveTo,
                              boolean enabled, int version) {
    }

    private final FinancialProductMapper mapper;

    public FinancialProductRepository(FinancialProductMapper mapper) {
        this.mapper = mapper;
    }

    /** 当前生效档位。 */
    public Optional<ProductRow> findEffective(String productCode, LocalDate bizDate) {
        List<Map<String, Object>> rows = mapper.findEffective(productCode, bizDate);
        return rows.stream().findFirst().map(FinancialProductRepository::toRow);
    }

    /** 同码全部版本。 */
    public List<ProductRow> findVersions(String productCode) {
        return mapper.findVersions(productCode).stream().map(FinancialProductRepository::toRow).toList();
    }

    /** 新增档位版本，返回自增主键。 */
    public long insert(String productCode, int productVersion, String productName, String scheme,
                       int termCount, int rateBp, Integer bonusMonth, Integer waiverCapBp,
                       LocalDate effectiveFrom, LocalDate effectiveTo, String traceId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("productCode", productCode);
        params.put("productVersion", productVersion);
        params.put("productName", productName);
        params.put("scheme", scheme);
        params.put("termCount", termCount);
        params.put("rateBp", rateBp);
        params.put("bonusMonth", bonusMonth);
        params.put("waiverCapBp", waiverCapBp);
        params.put("effectiveFrom", effectiveFrom);
        params.put("effectiveTo", effectiveTo);
        params.put("enabled", Boolean.TRUE);
        params.put("traceId", traceId);
        mapper.insert(params);
        Object id = params.get("productId");
        return id == null ? 0L : ((Number) id).longValue();
    }

    /** 旧版本封口停用；返回影响行数（0 = 版本冲突）。 */
    public int retire(long productId, LocalDate effectiveTo, int version) {
        return mapper.retire(productId, effectiveTo, version);
    }

    /** 行 → 不可变档案（缓存对象形态）。 */
    public static ProductProfile toProfile(ProductRow row) {
        return new ProductProfile(row.productCode(), row.productVersion(), row.productName(),
                row.scheme(), row.termCount(), row.rateBp(), row.bonusMonth(), row.waiverCapBp(),
                row.effectiveFrom(), row.effectiveTo(), row.enabled(), row.version());
    }

    private static ProductRow toRow(Map<String, Object> row) {
        return new ProductRow(
                RowValues.asLong(row, "product_id"),
                RowValues.asString(row, "product_code"),
                RowValues.asInt(row, "product_version"),
                RowValues.asString(row, "product_name"),
                RowValues.asString(row, "scheme"),
                RowValues.asInt(row, "term_count"),
                RowValues.asInt(row, "rate_bp"),
                row.get("bonus_month") == null ? null : RowValues.asInt(row, "bonus_month"),
                row.get("waiver_cap_bp") == null ? null : RowValues.asInt(row, "waiver_cap_bp"),
                RowValues.asLocalDate(row, "effective_from"),
                RowValues.asLocalDate(row, "effective_to"),
                RowValues.asBoolean(row, "enabled"),
                RowValues.asInt(row, "version"));
    }
}
