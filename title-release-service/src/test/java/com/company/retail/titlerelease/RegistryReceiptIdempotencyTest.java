package com.company.retail.titlerelease;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.lock.InProcessLockManager;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService;
import com.company.retail.titlerelease.application.TitleRepositoryPort;
import com.company.retail.titlerelease.mapper.ReleaseOrderMapper;
import com.company.retail.titlerelease.persistence.AttachmentRepository;
import com.company.retail.titlerelease.persistence.ReleaseOrderRepository;
import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部登记回执幂等单测（ADR-RF-013 回调幂等）。
 *
 * <p>覆盖：① 首次外部回执 → 解押闭环 + 发一次闭环事件；
 * ② 同一回执重复登记（外部方换 nonce 重推）→ 幂等返回，不重复落库、不重复推进状态、
 * 不重复发事件；③ 并发投递同一回执 → 只落一条回执、只发一次事件。</p>
 *
 * <p>幂等三道闸（ADR-RF-013）：分布式锁（串行化）→ 锁内重查既有回执 →
 * {@code uk_receipt_order_authority_ref} 唯一索引（最终权威，ON CONFLICT DO NOTHING）。</p>
 */
class RegistryReceiptIdempotencyTest {

    private static final String TRACE = "11111111-2222-3333-4444-555555555555";
    private static final long ORDER_ID = 31L;
    private static final long LOAN_ID = 41L;
    private static final long TITLE_ID = 51L;

    /** 内存解除订单仓储：模拟订单状态机推进 + 回执唯一索引（ON CONFLICT DO NOTHING）。 */
    static final class InMemoryReleaseOrderMapper implements ReleaseOrderMapper {
        final Map<Long, Map<String, Object>> orders = new ConcurrentHashMap<>();
        final List<Map<String, Object>> receipts = new CopyOnWriteArrayList<>();
        final AtomicLong receiptSeq = new AtomicLong(700);

        Map<String, Object> orderRow() {
            Map<String, Object> row = new HashMap<>();
            row.put("release_order_id", ORDER_ID);
            row.put("loan_id", LOAN_ID);
            row.put("title_id", TITLE_ID);
            row.put("status", "EXTERNAL_SUBMITTED");
            row.put("source", "EVENT");
            row.put("loan_settled_event_id", 9001L);
            row.put("external_submission_ref", "SUB-9001");
            row.put("confirmed_at", null);
            row.put("confirmation_authority", null);
            row.put("version", 0);
            return row;
        }

        @Override
        public List<Map<String, Object>> findOrderById(long releaseOrderId) {
            Map<String, Object> row = orders.get(releaseOrderId);
            return row == null ? List.of() : List.of(row);
        }

        @Override
        public List<Map<String, Object>> findOrderByLoanId(long loanId) {
            return List.of();
        }

        @Override
        public int insertOrder(Map<String, Object> params) {
            return 0;
        }

        @Override
        public int updateOrderStatus(long releaseOrderId, String status, Timestamp confirmedAt,
                                      String confirmationAuthority, String externalSubmissionRef,
                                      int version) {
            Map<String, Object> row = orders.get(releaseOrderId);
            if (row == null || ((Number) row.get("version")).intValue() != version) {
                return 0;   // 乐观锁冲突
            }
            row.put("status", status);
            row.put("version", version + 1);
            if (confirmedAt != null) {
                row.put("confirmed_at", confirmedAt);
            }
            if (confirmationAuthority != null) {
                row.put("confirmation_authority", confirmationAuthority);
            }
            return 1;
        }

        @Override
        public List<Long> findDocumentId(long releaseOrderId, String docType) {
            return List.of();
        }

        @Override
        public int upsertDocument(long releaseOrderId, String docType, Long attachmentId,
                                  String deliveredTo, String traceId) {
            return 0;
        }

        @Override
        public int attachDocument(long documentId, long attachmentId) {
            return 0;
        }

        @Override
        public List<Map<String, Object>> findDocuments(long releaseOrderId) {
            return List.of();
        }

        @Override
        public int insertReceipt(Map<String, Object> params) {
            long orderId = ((Number) params.get("releaseOrderId")).longValue();
            String authority = String.valueOf(params.get("authority"));
            String ref = params.get("receiptRef") == null ? "" : String.valueOf(params.get("receiptRef"));
            for (Map<String, Object> row : receipts) {
                if (((Number) row.get("release_order_id")).longValue() == orderId
                        && authority.equals(row.get("authority"))
                        && ref.equals(String.valueOf(row.get("receipt_ref")))) {
                    // ON CONFLICT (release_order_id, authority, COALESCE(receipt_ref,'')) DO NOTHING
                    return 0;
                }
            }
            long id = receiptSeq.incrementAndGet();
            Map<String, Object> row = new HashMap<>();
            row.put("receipt_id", id);
            row.put("release_order_id", orderId);
            row.put("authority", authority);
            row.put("receipt_ref", ref);
            row.put("received_at", Timestamp.from(Instant.now()));
            row.put("verified_by", params.get("verifiedBy"));
            row.put("raw_ref", params.get("rawRef"));
            receipts.add(row);
            params.put("receiptId", id);
            return 1;
        }

        @Override
        public List<Map<String, Object>> findReceiptByRef(long releaseOrderId, String authority,
                                                          String receiptRef) {
            String ref = receiptRef == null ? "" : receiptRef;
            return receipts.stream()
                    .filter(r -> ((Number) r.get("release_order_id")).longValue() == releaseOrderId)
                    .filter(r -> authority.equals(r.get("authority")))
                    .filter(r -> ref.equals(String.valueOf(r.get("receipt_ref"))))
                    .findFirst()
                    .map(List::of)
                    .orElseGet(List::of);
        }

        @Override
        public int countExternalReceipts(long releaseOrderId) {
            return (int) receipts.stream()
                    .filter(r -> ((Number) r.get("release_order_id")).longValue() == releaseOrderId)
                    .filter(r -> "EXTERNAL_REGISTRY".equals(r.get("authority")))
                    .count();
        }

        @Override
        public List<Map<String, Object>> findReceipts(long releaseOrderId) {
            return receipts.stream()
                    .filter(r -> ((Number) r.get("release_order_id")).longValue() == releaseOrderId)
                    .toList();
        }
    }

    /** 内存保留登记仓储：REGISTERED → RELEASE_REGISTERED → CLOSED（乐观锁 version 递增）。 */
    static final class InMemoryTitleRepository implements TitleRepositoryPort {
        final Map<Long, TitleRow> byTitle = new ConcurrentHashMap<>();
        private final AtomicLong seq = new AtomicLong(800);

        InMemoryTitleRepository() {
            TitleRow row = new TitleRow(TITLE_ID, LOAN_ID, "VIN-41", "REGISTERED", "REGISTERED",
                    Timestamp.from(Instant.now()), null, 0);
            byTitle.put(TITLE_ID, row);
        }

        @Override
        public long insert(long loanId, String vin, String status, String registrationMode,
                           Timestamp registeredAt, String traceId) {
            return 0;
        }

        @Override
        public java.util.Optional<TitleRow> findByLoanId(long loanId) {
            return byTitle.values().stream().filter(r -> r.loanId() == loanId).findFirst();
        }

        @Override
        public java.util.Optional<TitleRow> findById(long titleId) {
            return java.util.Optional.ofNullable(byTitle.get(titleId));
        }

        @Override
        public int updateStatus(long titleId, String status, Timestamp releasedAt, int version) {
            TitleRow row = byTitle.get(titleId);
            if (row == null || row.version() != version) {
                return 0;
            }
            byTitle.put(titleId, new TitleRow(row.titleId(), row.loanId(), row.vin(), status,
                    row.registrationMode(), row.registeredAt(), releasedAt, row.version() + 1));
            return 1;
        }

        @Override
        public int correctPlaceholderRegistration(long titleId, String placeholderVin, String vin,
                                                  String registrationMode, Timestamp registeredAt,
                                                  int version) {
            return 0;
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

    private TitleReleaseApplicationService service(InMemoryReleaseOrderMapper mapper,
                                                   InMemoryTitleRepository titles,
                                                   RecordingOutbox outbox, Path tempDir) {
        mapper.orders.put(ORDER_ID, mapper.orderRow());
        return new TitleReleaseApplicationService(titles,
                new ReleaseOrderRepository(mapper),
                new AttachmentRepository(null),
                new LocalStorageAttachmentStorage(tempDir.toString(), "test-token", 1024L),
                outbox,
                new TitleReleaseApplicationService.ObjectMapperHolder(new ObjectMapper()),
                new InProcessLockManager(),   // 单测不依赖 Redis：单进程锁（ADR-RF-013）
                3000L);
    }

    // ---------- 用例 ----------

    @Test
    void 首次外部回执_解押闭环并只发一次闭环事件(@TempDir Path tempDir) {
        InMemoryReleaseOrderMapper mapper = new InMemoryReleaseOrderMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        TitleReleaseApplicationService service = service(mapper, new InMemoryTitleRepository(), outbox, tempDir);

        var result = service.registerReceipt(ORDER_ID, "EXTERNAL_REGISTRY", "RCP-1", "REG-SIM-01",
                "raw-1", TRACE);

        assertEquals("CLOSED", result.status());
        assertTrue(result.releaseCompleted());
        assertEquals(1, mapper.receipts.size());
        assertEquals(1, outbox.published.size());
        assertEquals(OutboxPublisher.TITLE_RELEASE_REGISTERED, outbox.published.get(0).eventType());
    }

    @Test
    void 同一回执重复登记_幂等返回不重复落库不重复发事件(@TempDir Path tempDir) {
        InMemoryReleaseOrderMapper mapper = new InMemoryReleaseOrderMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        TitleReleaseApplicationService service = service(mapper, new InMemoryTitleRepository(), outbox, tempDir);

        var first = service.registerReceipt(ORDER_ID, "EXTERNAL_REGISTRY", "RCP-DUP", "REG-SIM-01",
                "raw-1", TRACE);
        // 外部方换 nonce 重推同一业务回执 → 幂等
        var again = service.registerReceipt(ORDER_ID, "EXTERNAL_REGISTRY", "RCP-DUP", "REG-SIM-01",
                "raw-1", TRACE);

        assertEquals(first.status(), again.status());
        assertTrue(again.releaseCompleted());
        assertTrue(again.message().contains("幂等"), "重复回执必须走幂等分支：" + again.message());
        assertEquals(1, mapper.receipts.size(), "同一回执只允许落一条");
        assertEquals(1, outbox.published.size(), "重复回执不得重复发闭环事件");
    }

    @Test
    void 并发投递同一回执_只落一条只发一次事件(@TempDir Path tempDir) throws Exception {
        InMemoryReleaseOrderMapper mapper = new InMemoryReleaseOrderMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        TitleReleaseApplicationService service = service(mapper, new InMemoryTitleRepository(), outbox, tempDir);

        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        Runnable task = () -> {
            try {
                start.await();
                service.registerReceipt(ORDER_ID, "EXTERNAL_REGISTRY", "RCP-CONC", "REG-SIM-01",
                        "raw-1", TRACE);
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

        assertTrue(errors.isEmpty(), "并发重复回执不得报错：" + errors);
        assertEquals(1, mapper.receipts.size(), "并发重复回执只允许落一条");
        assertEquals(1, outbox.published.size(), "并发重复回执只允许发一次闭环事件");
    }

    @Test
    void 自证回执_不得采信不落库(@TempDir Path tempDir) {
        InMemoryReleaseOrderMapper mapper = new InMemoryReleaseOrderMapper();
        RecordingOutbox outbox = new RecordingOutbox();
        TitleReleaseApplicationService service = service(mapper, new InMemoryTitleRepository(), outbox, tempDir);

        try {
            service.registerReceipt(ORDER_ID, "PARTNER_SELF_REPORT", "RCP-X", "DEALER", "raw-x", TRACE);
            assertFalse(true, "自证回执必须被拒绝");
        } catch (com.company.platform.exception.BusinessException e) {
            assertEquals(com.company.retail.domain.exception.RFErrorCode.RF_TITLE_002, e.getErrorCode());
        }

        assertTrue(mapper.receipts.isEmpty(), "自证回执不得落库");
        assertTrue(outbox.published.isEmpty());
    }
}
