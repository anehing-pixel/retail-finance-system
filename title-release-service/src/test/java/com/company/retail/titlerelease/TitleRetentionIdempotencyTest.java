package com.company.retail.titlerelease;

import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService;
import com.company.retail.titlerelease.application.TitleRepositoryPort;
import com.company.retail.titlerelease.persistence.JdbcAttachmentRepository;
import com.company.retail.titlerelease.persistence.JdbcReleaseOrderRepository;
import com.company.retail.titlerelease.storage.LocalStorageAttachmentStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留登记幂等兜底单测（uk_title_loan 并发竞态）。
 *
 * <p>背景：操作员主动登记（{@code POST /api/title-retentions}）与 LOAN_SETTLED 事件兜底建单
 * 会并发写同一债权。旧实现是「先查后插」，唯一键异常直接冒泡 → HTTP 500
 * （前端冒烟 F11「保留登记 REGISTERED」偶发失败，复跑才绿）。</p>
 *
 * <p>新契约：insert 遇 uk_title_loan 冲突返回 0（{@code ON CONFLICT DO NOTHING}），
 * 调用方幂等返回既有记录——不抛异常、不重复发布登记事件。</p>
 */
class TitleRetentionIdempotencyTest {

    private static final String TRACE = "11111111-2222-3333-4444-555555555555";

    // ---------- 假件 ----------

    /** 内存保留登记仓储：可模拟「查时不存在、插入时被抢先」的竞态。 */
    static final class InMemoryTitleRepository implements TitleRepositoryPort {
        final Map<Long, TitleRow> byLoan = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(500);
        /** 竞态开关：true 时下一次 insert 视为被对方抢先（唯一键冲突）→ 返回 0。 */
        boolean conflictOnNextInsert = false;

        @Override
        public long insert(long loanId, String vin, String status, String registrationMode,
                           Timestamp registeredAt, String traceId) {
            if (conflictOnNextInsert) {
                conflictOnNextInsert = false;
                // 模拟对方（LOAN_SETTLED 兜底建单）已提交：vin 用 UNKNOWN-{loanId}
                if (!byLoan.containsKey(loanId)) {
                    byLoan.put(loanId, new TitleRow(seq.incrementAndGet(), loanId, "UNKNOWN-" + loanId,
                            "REGISTERED", "REGISTERED", Timestamp.from(Instant.now()), null, 0));
                }
                return 0;
            }
            if (byLoan.containsKey(loanId)) {
                return 0; // uk_title_loan 冲突语义：不插入
            }
            long id = seq.incrementAndGet();
            byLoan.put(loanId, new TitleRow(id, loanId, vin, status, registrationMode,
                    registeredAt, null, 0));
            return id;
        }

        @Override
        public Optional<TitleRow> findByLoanId(long loanId) {
            return Optional.ofNullable(byLoan.get(loanId));
        }

        @Override
        public Optional<TitleRow> findById(long titleId) {
            return byLoan.values().stream().filter(r -> r.titleId() == titleId).findFirst();
        }

        @Override
        public int updateStatus(long titleId, String status, Timestamp releasedAt, int version) {
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

    private TitleReleaseApplicationService service(TitleRepositoryPort repo, OutboxPublisher outbox,
                                                   Path tempDir) {
        return new TitleReleaseApplicationService(repo,
                new JdbcReleaseOrderRepository(null),   // 本组用例不触达解除订单
                new JdbcAttachmentRepository(null),      // 本组用例不触达附件
                new LocalStorageAttachmentStorage(tempDir.toString(), "test-token", 1024L),
                outbox,
                new TitleReleaseApplicationService.ObjectMapperHolder(new ObjectMapper()));
    }

    // ---------- 用例 ----------

    @Test
    void 正常登记_写入新记录并发布登记事件(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        var view = service.registerRetention(40L, "VIN-40", false, TRACE);

        assertTrue(view.titleId() > 0);
        assertEquals("VIN-40", view.vin());
        assertEquals("REGISTERED", view.status());
        assertEquals(1, outbox.published.size());
        assertEquals(OutboxPublisher.TITLE_RETENTION_REGISTERED, outbox.published.get(0).eventType());
    }

    @Test
    void 查时已存在_幂等返回既有记录且不重复发布(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        long existingId = repo.insert(41L, "VIN-41", "REGISTERED", "REGISTERED",
                Timestamp.from(Instant.now()), null);
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        var view = service.registerRetention(41L, "VIN-41", false, TRACE);

        assertEquals(existingId, view.titleId());
        assertTrue(outbox.published.isEmpty());
    }

    @Test
    void 并发冲突_插入时对方抢先则幂等返回不抛500(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        repo.conflictOnNextInsert = true;   // 对方（LOAN_SETTLED 兜底）抢先提交
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        var view = service.registerRetention(42L, "VIN-42", false, TRACE);

        assertTrue(view.titleId() > 0);
        assertEquals("UNKNOWN-42", view.vin());   // 兜底建的那条（真实 vin 以登记变更流程订正）
        assertEquals("REGISTERED", view.status());
        assertTrue(outbox.published.isEmpty(), "冲突分支不得重复发布登记事件");
        assertEquals(1, repo.byLoan.size());
    }

    @Test
    void 兜底建单_缺记录时补一条REGISTERED(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        var service = service(repo, new RecordingOutbox(), tempDir);

        service.ensureRetentionForSettledLoan(43L);

        assertEquals(1, repo.byLoan.size());
        assertEquals("UNKNOWN-43", repo.byLoan.get(43L).vin());
        assertEquals("REGISTERED", repo.byLoan.get(43L).status());
    }

    @Test
    void 兜底建单_已有记录时不重复建(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        repo.insert(44L, "VIN-44", "REGISTERED", "REGISTERED", Timestamp.from(Instant.now()), null);
        var service = service(repo, new RecordingOutbox(), tempDir);

        service.ensureRetentionForSettledLoan(44L);

        assertEquals(1, repo.byLoan.size());
    }

    @Test
    void 兜底建单_并发冲突时幂等跳过不抛异常(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        repo.conflictOnNextInsert = true;
        var service = service(repo, new RecordingOutbox(), tempDir);

        assertDoesNotThrow(() -> service.ensureRetentionForSettledLoan(45L));

        assertEquals(1, repo.byLoan.size());
    }
}
