package com.company.retail.core.application;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.cache.CacheInvalidator;
import com.company.retail.common.cache.TwoLevelCache;
import com.company.retail.core.domain.ProductProfile;
import com.company.retail.core.persistence.FinancialProductRepository;
import com.company.retail.domain.exception.RFErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 产品档位目录用例（读多写少，读走两级缓存）。
 *
 * <p><b>为什么要有这层</b>：还款计划生成时的利率原本是调用方入参且不落库，
 * 计划生成后无法复现当时算法。本用例把「档位」变成一等配置，
 * 并在生成计划时把<b>快照</b>落进计划表——改配置不改历史计划。</p>
 *
 * <p><b>缓存失效时机</b>：改档位走「旧版本封口停用 + 新版本插入」同一事务，
 * <b>提交后</b>再删两级缓存（{@link CacheInvalidator}）。
 * 提交前失效会被并发回源把旧值又写回缓存（见该类注释）。</p>
 */
@Service
public class ProductCatalogService {

    /** 业务日口径：全系统 JST（R00 §5）。 */
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /** 缓存键前缀（再拼上键前缀配置形成完整 key）。 */
    private static final String CACHE_KEY_PREFIX = "product:";

    private final FinancialProductRepository repository;
    private final TwoLevelCache cache;

    public ProductCatalogService(FinancialProductRepository repository, TwoLevelCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    /**
     * 当前生效档位（两级缓存：Caffeine L1 → Redis L2 → 数据库）。
     *
     * @throws BusinessException 档位不存在或当前未生效
     */
    public ProductProfile findEffective(String productCode) {
        String code = requireCode(productCode);
        return cache.get(CACHE_KEY_PREFIX + code, ProductProfile.class,
                        () -> repository.findEffective(code, LocalDate.now(JST))
                                .map(FinancialProductRepository::toProfile)
                                .orElse(null))
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "产品档位不存在或未生效", Map.of("productCode", code)));
    }

    /** 同码全部版本（变更审计视图，不走缓存）。 */
    public List<ProductProfile> versions(String productCode) {
        String code = requireCode(productCode);
        return repository.findVersions(code).stream()
                .map(FinancialProductRepository::toProfile).toList();
    }

    /**
     * 新建档位（首个版本）。
     *
     * @throws BusinessException 档位已存在或参数非法
     */
    @Transactional
    public ProductProfile create(ProductCommand cmd, String traceId) {
        String code = requireCode(cmd.productCode());
        if (!repository.findVersions(code).isEmpty()) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    "该产品档位已存在，请走改版接口", Map.of("productCode", code));
        }
        validate(cmd);
        LocalDate from = cmd.effectiveFrom() == null ? LocalDate.now(JST) : cmd.effectiveFrom();
        repository.insert(code, 1, cmd.productName(), cmd.scheme(), cmd.termCount(), cmd.rateBp(),
                cmd.bonusMonth(), cmd.waiverCapBp(), from, null, traceId);
        CacheInvalidator.evictAfterCommit(cache, CACHE_KEY_PREFIX + code);
        return repository.findEffective(code, from)
                .map(FinancialProductRepository::toProfile)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "产品档位写入后读取失败", Map.of("productCode", code)));
    }

    /**
     * 配置改版：旧版本封口停用 + 新版本插入（同一事务），提交后失效缓存。
     *
     * <p>不做原地覆盖：旧版本行仍在表里，变更审计可追；历史计划另有快照，不依赖回查。</p>
     *
     * @throws BusinessException 档位不存在、参数非法或并发改配置
     */
    @Transactional
    public ProductProfile revise(String productCode, ProductCommand cmd, String traceId) {
        String code = requireCode(productCode);
        validate(cmd);
        LocalDate bizDate = LocalDate.now(JST);
        FinancialProductRepository.ProductRow current = repository.findEffective(code, bizDate)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "产品档位不存在或未生效", Map.of("productCode", code)));
        if (repository.retire(current.productId(), bizDate, current.version()) == 0) {
            throw new BusinessException(RFErrorCode.RF_CONFLICT_001,
                    "产品档位并发修改冲突（乐观锁 version）", Map.of("productCode", code));
        }
        repository.insert(code, current.productVersion() + 1, cmd.productName(), cmd.scheme(),
                cmd.termCount(), cmd.rateBp(), cmd.bonusMonth(), cmd.waiverCapBp(), bizDate, null,
                traceId);
        CacheInvalidator.evictAfterCommit(cache, CACHE_KEY_PREFIX + code);
        return repository.findEffective(code, bizDate)
                .map(FinancialProductRepository::toProfile)
                .orElseThrow(() -> new BusinessException(RFErrorCode.RF_VALID_001,
                        "产品档位写入后读取失败", Map.of("productCode", code)));
    }

    private static String requireCode(String productCode) {
        if (productCode == null || productCode.isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "productCode 必填");
        }
        return productCode.trim();
    }

    private static void validate(ProductCommand cmd) {
        if (cmd.productName() == null || cmd.productName().isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "productName 必填");
        }
        if (cmd.scheme() == null || cmd.scheme().isBlank()) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "scheme 必填");
        }
        if (cmd.termCount() <= 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "期次必须为正整数");
        }
        if (cmd.rateBp() < 0) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "月利率基点必须非负");
        }
        if (cmd.bonusMonth() != null && (cmd.bonusMonth() < 1 || cmd.bonusMonth() > 12)) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "奖金月必须是 1-12");
        }
        if (cmd.waiverCapBp() != null && (cmd.waiverCapBp() < 0 || cmd.waiverCapBp() > 1000)) {
            throw new BusinessException(RFErrorCode.RF_VALID_001, "减免上限（千分比）必须在 0-1000");
        }
    }

    /**
     * 档位定义命令。
     *
     * @param productCode  档位码（业务主键）
     * @param productName  档位名
     * @param scheme       适用交易形态（INSTALLMENT_CREDIT / MONEY_LENDING）
     * @param termCount    期数
     * @param rateBp       月利率基点（200 = 2.0%/月；0 = 0 息）
     * @param bonusMonth   允许的奖金月（1-12）；null = 不支持
     * @param waiverCapBp  提前结清减免上限（千分比）；null = 不设上限（待法务核实）
     * @param effectiveFrom 生效日（含）；null = 当日
     */
    public record ProductCommand(String productCode, String productName, String scheme, int termCount,
                                 int rateBp, Integer bonusMonth, Integer waiverCapBp,
                                 LocalDate effectiveFrom) {
    }
}
