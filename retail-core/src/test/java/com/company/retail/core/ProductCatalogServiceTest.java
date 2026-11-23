package com.company.retail.core;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.cache.TwoLevelCache;
import com.company.retail.core.application.ProductCatalogService;
import com.company.retail.core.domain.ProductProfile;
import com.company.retail.core.persistence.FinancialProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产品档位目录用例单测：读走缓存、写后主动失效、乐观锁冲突拒绝。
 *
 * <p>本仓库服务侧单测不引 Mockito（手写替身），故此处用两个 fake：目录仓储与两级缓存。</p>
 */
class ProductCatalogServiceTest {

    private FakeProductRepository repository;
    private FakeTwoLevelCache cache;
    private ProductCatalogService service;

    @BeforeEach
    void setUp() {
        repository = new FakeProductRepository();
        cache = new FakeTwoLevelCache();
        service = new ProductCatalogService(repository, cache);
    }

    private static ProductProfile profile(int version, int rateBp) {
        return new ProductProfile("AUTO_LOAN_36_0", version, "汽车贷款 36 期 0 息", "MONEY_LENDING",
                36, rateBp, null, null, LocalDate.of(2026, 1, 1), null, true, 0);
    }

    @Test
    void findEffective_cacheHit_returnsProfile() {
        cache.store.put("product:AUTO_LOAN_36_0", profile(1, 0));

        ProductProfile got = service.findEffective("AUTO_LOAN_36_0");

        assertEquals("AUTO_LOAN_36_0", got.productCode());
        assertEquals(0, got.rateBp());
        assertEquals(0, repository.findCalls, "缓存命中不应打库");
    }

    @Test
    void findEffective_cacheMiss_hitsRepository_thenCaches() {
        repository.effective.put("AUTO_LOAN_36_0", row(1, 0, 3));

        ProductProfile got = service.findEffective("AUTO_LOAN_36_0");

        assertEquals(0, got.rateBp());
        assertEquals(1, repository.findCalls);
        assertTrue(cache.store.containsKey("product:AUTO_LOAN_36_0"), "回源结果应回填缓存");
    }

    @Test
    void findEffective_notFound_throwsBusinessError() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.findEffective("NOT_EXIST"));
        assertEquals("RF_VALID_001", e.getErrorCode().getCode());
    }

    @Test
    void revise_retiresOldVersion_insertsNewVersion_andEvictsCache() {
        repository.effective.put("AUTO_LOAN_36_0", row(1, 0, 3));

        ProductCatalogService.ProductCommand cmd = new ProductCatalogService.ProductCommand(
                "AUTO_LOAN_36_0", "新名", "MONEY_LENDING", 36, 50, null, null, null);
        service.revise("AUTO_LOAN_36_0", cmd, "trace-1");

        assertEquals(1, repository.retireCalls);
        assertEquals(3, repository.retireVersion, "封口旧版本要走乐观锁 version");
        assertEquals(1, repository.inserts.size());
        assertTrue(repository.inserts.get(0).contains("rateBp=50"), repository.inserts.get(0));
        assertTrue(repository.inserts.get(0).contains("version=2"), "新版本号应递增");
        // 无活动事务时立即失效（真实运行时由 afterCommit 钩子执行）
        assertEquals(List.of("product:AUTO_LOAN_36_0"), cache.evicted);
    }

    @Test
    void revise_concurrentModify_rejected() {
        repository.effective.put("AUTO_LOAN_36_0", row(1, 0, 3));
        repository.retireResult = 0;

        ProductCatalogService.ProductCommand cmd = new ProductCatalogService.ProductCommand(
                "AUTO_LOAN_36_0", "新名", "MONEY_LENDING", 36, 50, null, null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.revise("AUTO_LOAN_36_0", cmd, "trace-1"));
        assertEquals("RF_CONFLICT_001", e.getErrorCode().getCode());
        assertTrue(repository.inserts.isEmpty(), "乐观锁冲突时不得写入新版本");
    }

    @Test
    void create_insertsFirstVersion_andEvictsCache() {
        ProductCatalogService.ProductCommand cmd = new ProductCatalogService.ProductCommand(
                "NEW_PRODUCT", "新档位", "MONEY_LENDING", 36, 0, null, null, null);
        service.create(cmd, "trace-1");

        assertEquals(1, repository.inserts.size());
        assertTrue(repository.inserts.get(0).contains("version=1"));
        assertEquals(List.of("product:NEW_PRODUCT"), cache.evicted);
    }

    @Test
    void create_duplicateCode_rejected() {
        repository.versions.put("AUTO_LOAN_36_0", List.of(row(1, 0, 0)));

        ProductCatalogService.ProductCommand cmd = new ProductCatalogService.ProductCommand(
                "AUTO_LOAN_36_0", "名", "MONEY_LENDING", 36, 0, null, null, null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(cmd, "trace-1"));
        assertEquals("RF_CONFLICT_001", e.getErrorCode().getCode());
    }

    @Test
    void create_invalidCommand_rejected() {
        ProductCatalogService.ProductCommand cmd = new ProductCatalogService.ProductCommand(
                "X", "名", "MONEY_LENDING", 0, 0, null, null, null);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(cmd, "trace-1"));
        assertEquals("RF_VALID_001", e.getErrorCode().getCode());
    }

    private static FinancialProductRepository.ProductRow row(int version, int rateBp, int lockVersion) {
        return new FinancialProductRepository.ProductRow(100L, "AUTO_LOAN_36_0", version, "档位",
                "MONEY_LENDING", 36, rateBp, null, null, LocalDate.of(2026, 1, 1), null, true,
                lockVersion);
    }

    /** 目录仓储替身：记录调用与写入，供断言。 */
    static final class FakeProductRepository extends FinancialProductRepository {

        final Map<String, ProductRow> effective = new LinkedHashMap<>();
        final Map<String, List<ProductRow>> versions = new LinkedHashMap<>();
        final List<String> inserts = new ArrayList<>();
        int findCalls;
        int retireCalls;
        int retireVersion = -1;
        int retireResult = 1;

        FakeProductRepository() {
            super(null);
        }

        @Override
        public Optional<ProductRow> findEffective(String productCode, LocalDate bizDate) {
            findCalls++;
            return Optional.ofNullable(effective.get(productCode));
        }

        @Override
        public List<ProductRow> findVersions(String productCode) {
            return versions.getOrDefault(productCode, List.of());
        }

        @Override
        public long insert(String productCode, int productVersion, String productName, String scheme,
                           int termCount, int rateBp, Integer bonusMonth, Integer waiverCapBp,
                           LocalDate effectiveFrom, LocalDate effectiveTo, String traceId) {
            inserts.add(productCode + "#version=" + productVersion + "#rateBp=" + rateBp);
            effective.put(productCode, new ProductRow(200L, productCode, productVersion, productName,
                    scheme, termCount, rateBp, bonusMonth, waiverCapBp, effectiveFrom, effectiveTo,
                    true, 0));
            return 200L;
        }

        @Override
        public int retire(long productId, LocalDate effectiveTo, int version) {
            retireCalls++;
            retireVersion = version;
            return retireResult;
        }
    }

    /** 两级缓存替身：直接存对象，不做序列化。 */
    static final class FakeTwoLevelCache extends TwoLevelCache {

        final Map<String, Object> store = new LinkedHashMap<>();
        final List<String> evicted = new ArrayList<>();

        FakeTwoLevelCache() {
            super("test:", Duration.ofSeconds(60), Duration.ofSeconds(60), 100,
                    new ObjectMapper(), null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String key, Class<T> type, Supplier<T> loader) {
            if (store.containsKey(key)) {
                return Optional.ofNullable((T) store.get(key));
            }
            T value = loader.get();
            if (value != null) {
                store.put(key, value);
            }
            return Optional.ofNullable(value);
        }

        @Override
        public void evict(String key) {
            evicted.add(key);
            store.remove(key);
        }
    }
}
