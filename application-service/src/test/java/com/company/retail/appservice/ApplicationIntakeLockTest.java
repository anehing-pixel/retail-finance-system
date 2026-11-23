package com.company.retail.appservice;

import com.company.platform.exception.BusinessException;
import com.company.retail.appservice.app.ApplicationService;
import com.company.retail.appservice.domain.Application;
import com.company.retail.appservice.mapper.ApplicationMapper;
import com.company.retail.appservice.mapper.IdempotencyMapper;
import com.company.retail.appservice.repo.ApplicationIdempotencyStore;
import com.company.retail.appservice.repo.ApplicationRepository;
import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.lock.DistributedLockManager;
import com.company.retail.common.lock.InProcessLockManager;
import com.company.retail.domain.exception.RFErrorCode;
import com.company.retail.common.persistence.RowValues;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进件并发去重单测（ADR-RF-013 分布式锁 + 唯一索引双兜底）。
 *
 * <p>覆盖：① 并发同客户同 VIN 只受理一条（另一条命中 RF_APP_001）；
 * ② 锁服务不可用 → fail-closed 拒绝受理且不落库；
 * ③ 并发同幂等键 → 后到者返回首次受理结果（replayed=true）；
 * ④ 同幂等键异指纹 → RF_CONFLICT_001。</p>
 *
 * <p>单测不依赖 Redis：锁用单进程实现（互斥语义与生产适配器同款），
 * 仓储用内存假件（对齐 {@code ON CONFLICT DO NOTHING} 与唯一索引语义）。</p>
 */
class ApplicationIntakeLockTest {

    private static final String TRACE = "11111111-2222-3333-4444-555555555555";

    /** 内存申请仓储：模拟 uk_app_idempotency 唯一索引与 ON CONFLICT DO NOTHING 语义。 */
    static final class InMemoryApplicationMapper implements ApplicationMapper {
        final Map<Long, Map<String, Object>> rows = new ConcurrentHashMap<>();
        final Map<Long, String> vins = new ConcurrentHashMap<>();
        final Map<String, Long> byIdempotencyKey = new ConcurrentHashMap<>();
        final AtomicLong seq = new AtomicLong(900);

        @Override
        public List<Map<String, Object>> findPriorDuplicateSubmittedAt(long customerId, String vin) {
            Instant latest = null;
            for (Map<String, Object> row : rows.values()) {
                if (RowValues.asLong(row, "customer_id") != customerId) {
                    continue;
                }
                if (!vin.equals(vins.get(RowValues.asLong(row, "application_id")))) {
                    continue;
                }
                String status = RowValues.asString(row, "status");
                if (List.of("DECLINED", "WITHDRAWN", "CANCELLED", "EXPIRED", "CONTRACTED").contains(status)) {
                    continue;
                }
                Instant at = RowValues.asInstant(row, "submitted_at");
                if (at != null && (latest == null || at.isAfter(latest))) {
                    latest = at;
                }
            }
            return latest == null ? List.of() : List.of(Map.of("submitted_at", Timestamp.from(latest)));
        }

        @Override
        public int insertApplication(Map<String, Object> params) {
            String key = String.valueOf(params.get("idempotencyKey"));
            if (byIdempotencyKey.containsKey(key)) {
                // ON CONFLICT (idempotency_key) DO NOTHING：不插入、生成键不回填
                return 0;
            }
            long id = seq.incrementAndGet();
            Map<String, Object> row = new HashMap<>();
            row.put("application_id", id);
            row.put("application_no", params.get("applicationNo"));
            row.put("customer_id", params.get("customerId"));
            row.put("dealer_partner_id", params.get("dealerPartnerId"));
            row.put("status", "SUBMITTED");
            row.put("transaction_scheme", params.get("transactionScheme"));
            row.put("channel", params.get("channel"));
            row.put("submitted_at", params.get("submittedAt") instanceof Instant i
                    ? Timestamp.from(i) : params.get("submittedAt"));
            row.put("idempotency_key", key);
            row.put("request_fingerprint", params.get("requestFingerprint"));
            row.put("consent_ref", params.get("consentRef"));
            rows.put(id, row);
            byIdempotencyKey.put(key, id);
            params.put("applicationId", id);   // 生成键回填
            return 1;
        }

        @Override
        public int insertItem(long applicationId, String vin, String model, long cashPrice,
                              long accessoriesAmount, String traceId) {
            vins.put(applicationId, vin);
            return 1;
        }

        @Override
        public List<Map<String, Object>> findById(long applicationId) {
            Map<String, Object> row = rows.get(applicationId);
            return row == null ? List.of() : List.of(row);
        }

        @Override
        public List<Map<String, Object>> findByIdempotencyKey(String idempotencyKey) {
            Long id = byIdempotencyKey.get(idempotencyKey);
            return id == null ? List.of() : List.of(rows.get(id));
        }

        @Override
        public List<Map<String, Object>> findFingerprintByIdempotencyKey(String idempotencyKey) {
            Long id = byIdempotencyKey.get(idempotencyKey);
            if (id == null) {
                return List.of();
            }
            return List.of(Map.of("request_fingerprint",
                    String.valueOf(rows.get(id).get("request_fingerprint"))));
        }

        @Override
        public int updateStatus(long applicationId, String status) {
            Map<String, Object> row = rows.get(applicationId);
            if (row == null) {
                return 0;
            }
            row.put("status", status);
            return 1;
        }
    }

    /** 幂等 Mapper 假件：幂等记录与申请行同源（core.applications 的幂等键 + 指纹两列）。 */
    static final class FakeIdempotencyMapper implements IdempotencyMapper {
        private final InMemoryApplicationMapper mapper;

        FakeIdempotencyMapper(InMemoryApplicationMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public List<Map<String, Object>> findIdempotencyRows(String key) {
            return mapper.findByIdempotencyKey(key).stream().map(r -> {
                Map<String, Object> row = new HashMap<>();
                row.put("idempotency_key", r.get("idempotency_key"));
                row.put("request_fingerprint", r.get("request_fingerprint"));
                row.put("created_at", r.get("submitted_at"));
                return row;
            }).toList();
        }

        @Override
        public int updateRequestFingerprint(String requestFingerprint, String key) {
            return 0;   // 幂等记录与申请行同生共死，无需单独补写
        }

        @Override
        public List<Long> findApplicationIdsByKey(String key) {
            return List.of();
        }
    }

    /** 记录型 outbox：发布前执行与生产实现相同的 EventPayloadGuard 白名单校验（INV-4）。 */
    static final class RecordingOutbox implements OutboxPublisher {
        record Published(String eventType, String aggregateKey, String payload) {
        }

        final List<Published> published = new ArrayList<>();

        @Override
        public void publish(String eventType, String aggregateKey, String payload) {
            EventPayloadGuard.validate(eventType, payload);
            published.add(new Published(eventType, aggregateKey, payload));
        }
    }

    private ApplicationService service(InMemoryApplicationMapper mapper, RecordingOutbox outbox,
                                       DistributedLockManager locks) {
        return new ApplicationService(new ApplicationRepository(mapper),
                new ApplicationIdempotencyStore(new FakeIdempotencyMapper(mapper)),
                outbox, locks, 72L, 3000L);
    }

    private ApplicationService.SubmitCommand cmd(long customerId, String vin) {
        return new ApplicationService.SubmitCommand(customerId, 7L, "INSTALLMENT_CREDIT", "PORTAL",
                99L, new Application.Item(vin, "MODEL-X", 1_500_000L, 0L));
    }

    // ---------- 用例 ----------

    @Test
    void 并发同客户同车_只受理一条另一条命中重复申请() throws Exception {
        InMemoryApplicationMapper mapper = new InMemoryApplicationMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        ApplicationService service = service(mapper, outbox, new InProcessLockManager());

        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        List<ApplicationService.Result> results = new ArrayList<>();

        Runnable taskA = () -> {
            await(start);
            try {
                results.add(service.submit(cmd(100L, "VIN-CONC-1"), "IK-A", "fp-A", TRACE));
            } catch (Throwable e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        };
        Runnable taskB = () -> {
            await(start);
            try {
                results.add(service.submit(cmd(100L, "VIN-CONC-1"), "IK-B", "fp-B", TRACE));
            } catch (Throwable e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        };
        Thread t1 = new Thread(taskA);
        Thread t2 = new Thread(taskB);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(10000);
        t2.join(10000);

        assertEquals(1, results.size(), "并发受理只应有一条成功");
        assertEquals(1, errors.size(), "另一条必须被去重窗口拦下");
        BusinessException ex = (BusinessException) errors.get(0);
        assertEquals(RFErrorCode.RF_APP_001, ex.getErrorCode(), "并发重复进件应命中 RF_APP_001");
        assertEquals(1, mapper.rows.size(), "库内只允许一条申请");
        assertEquals(1, outbox.published.size(), "只发一次 APPLICATION_SUBMITTED");
    }

    @Test
    void 锁服务不可用_failClosed拒绝受理且不落库() {
        InMemoryApplicationMapper mapper = new InMemoryApplicationMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        // 泛型方法不能用 lambda 实现 → 用匿名类表达「锁服务不可用」
        DistributedLockManager broken = new DistributedLockManager() {
            @Override
            public <T> T callWithLock(String lockKey, java.time.Duration waitTime,
                                      java.util.function.Supplier<T> action) {
                throw new BusinessException(RFErrorCode.RF_LOCK_002,
                        Map.of("reason", "REDIS_UNAVAILABLE"));
            }
        };
        ApplicationService service = service(mapper, outbox, broken);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(cmd(101L, "VIN-LOCKDOWN"), "IK-LOCK", "fp-L", TRACE));

        assertEquals(RFErrorCode.RF_LOCK_002, ex.getErrorCode());
        assertEquals(503, ex.getErrorCode().getHttpStatus());
        assertTrue(mapper.rows.isEmpty(), "锁不可用时不得落库");
        assertTrue(outbox.published.isEmpty(), "锁不可用时不得发事件");
    }

    @Test
    void 并发同幂等键_后到者返回首次受理结果() throws Exception {
        InMemoryApplicationMapper mapper = new InMemoryApplicationMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        ApplicationService service = service(mapper, outbox, new InProcessLockManager());

        CountDownLatch start = new CountDownLatch(1);
        List<ApplicationService.Result> results = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        Runnable task = () -> {
            await(start);
            try {
                results.add(service.submit(cmd(102L, "VIN-SAMEKEY"), "IK-SAME", "fp-same", TRACE));
            } catch (Throwable e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        };
        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        start.countDown();
        t1.join(10000);
        t2.join(10000);

        assertTrue(errors.isEmpty(), "同幂等键并发不得报错：" + errors);
        assertEquals(2, results.size());
        long firstId = results.get(0).applicationId();
        assertEquals(firstId, results.get(1).applicationId(), "同幂等键并发必须返回同一申请号");
        assertEquals(1, results.stream().filter(r -> !r.replayed()).count(), "只有一条是首次受理");
        assertEquals(1, results.stream().filter(ApplicationService.Result::replayed).count(), "另一条是幂等重放");
        assertEquals(1, mapper.rows.size());
        assertEquals(1, outbox.published.size(), "重放不得重复发事件");
    }

    @Test
    void 同幂等键异指纹_冲突拒绝() {
        InMemoryApplicationMapper mapper = new InMemoryApplicationMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        ApplicationService service = service(mapper, outbox, new InProcessLockManager());

        service.submit(cmd(103L, "VIN-CONFLICT"), "IK-CONFLICT", "fp-1", TRACE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(cmd(103L, "VIN-CONFLICT"), "IK-CONFLICT", "fp-2", TRACE));

        assertEquals(RFErrorCode.RF_CONFLICT_001, ex.getErrorCode());
        assertEquals(1, mapper.rows.size());
        assertEquals(1, outbox.published.size());
    }

    @Test
    void 缺幂等键_拒绝受理() {
        InMemoryApplicationMapper mapper = new InMemoryApplicationMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        ApplicationService service = service(mapper, outbox, new InProcessLockManager());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(cmd(104L, "VIN-NOKEY"), " ", "fp", TRACE));

        assertEquals(RFErrorCode.RF_VALID_001, ex.getErrorCode());
        assertTrue(mapper.rows.isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
