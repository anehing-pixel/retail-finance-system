package com.company.retail.titlerelease;

import com.company.platform.exception.BusinessException;
import com.company.retail.common.contract.EventPayloadGuard;
import com.company.retail.common.contract.OutboxPublisher;
import com.company.retail.common.lock.InProcessLockManager;
import com.company.retail.titlerelease.application.TitleReleaseApplicationService;
import com.company.retail.titlerelease.application.TitleRepositoryPort;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留登记幂等与「登记订正 + 事件补发」单测（uk_title_loan 并发竞态）。
 *
 * <p>背景一：操作员主动登记（{@code POST /api/title-retentions}）与 LOAN_SETTLED 事件兜底建单
 * 会并发写同一债权。旧实现是「先查后插」，唯一键异常直接冒泡 → HTTP 500
 * （前端冒烟 F11「保留登记 REGISTERED」偶发失败，复跑才绿）。新契约：insert 遇 uk_title_loan
 * 冲突返回 0（{@code ON CONFLICT DO NOTHING}），调用方幂等处理——不抛异常。</p>
 *
 * <p>背景二（用户裁决 2026-11-23）：LOAN_SETTLED 兜底建的是**挡板占位行**
 * （{@code vin=UNKNOWN-<loanId>}），它不是「已登记」的业务事实。若占位行先于操作员正式登记落库，
 * 旧语义命中「已存在 → 幂等返回」→ <b>登记事件永不发出</b>，证据链缺「所有权保留登记」这一环
 * （E2E S11 的 TITLE_RETENTION_REGISTERED 断言失败）。</p>
 *
 * <p>新语义：操作员正式登记命中占位行 → <b>订正 VIN + 补发一次</b>登记事件；
 * 订正走乐观锁 + 占位 VIN 条件，并发/重放只订正一次、只补发一次事件。</p>
 */
class TitleRetentionIdempotencyTest {

    private static final String TRACE = "11111111-2222-3333-4444-555555555555";

    // ---------- 假件 ----------

    /** 内存保留登记仓储：可模拟「查时不存在、插入时被抢先」「订正时对方已抢先」两类竞态。 */
    static final class InMemoryTitleRepository implements TitleRepositoryPort {
        final Map<Long, TitleRow> byLoan = new LinkedHashMap<>();
        private final AtomicLong seq = new AtomicLong(500);
        /** 竞态开关：true 时下一次 insert 视为被对方抢先（唯一键冲突）→ 返回 0。 */
        boolean conflictOnNextInsert = false;
        /** 陈旧读模拟：下一次 findByLoanId 返回该快照（模拟「读到占位行后对方已订正」）。 */
        TitleRow staleRowOnce = null;

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
            if (staleRowOnce != null) {
                TitleRow stale = staleRowOnce;
                staleRowOnce = null;
                return Optional.of(stale);
            }
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

        /** 登记订正：三重守卫（title_id + version + 占位 VIN），与生产 JDBC 实现同语义。 */
        @Override
        public int correctPlaceholderRegistration(long titleId, String placeholderVin, String vin,
                                                  String registrationMode, Timestamp registeredAt, int version) {
            TitleRow row = byLoan.values().stream().filter(r -> r.titleId() == titleId).findFirst().orElse(null);
            if (row == null || row.version() != version || !row.vin().equals(placeholderVin)) {
                return 0;   // 已被他人订正 / 版本不符 / 非占位行 → 幂等，不补发事件
            }
            // 订正不改 status（同态订正）
            byLoan.put(row.loanId(), new TitleRow(row.titleId(), row.loanId(), vin, row.status(),
                    registrationMode, registeredAt, row.releasedAt(), row.version() + 1));
            return 1;
        }

        /** 测试辅助：直接改行状态（模拟解押已闭环）。 */
        void forceStatus(long loanId, String status) {
            TitleRow row = byLoan.get(loanId);
            byLoan.put(loanId, new TitleRow(row.titleId(), row.loanId(), row.vin(), status,
                    row.registrationMode(), row.registeredAt(), row.releasedAt(), row.version()));
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
                new ReleaseOrderRepository(null),   // 本组用例不触达解除订单
                new AttachmentRepository(null),      // 本组用例不触达附件
                new LocalStorageAttachmentStorage(tempDir.toString(), "test-token", 1024L),
                outbox,
                new TitleReleaseApplicationService.ObjectMapperHolder(new ObjectMapper()),
                new InProcessLockManager(),   // 单测不依赖 Redis：单进程锁（ADR-RF-012）
                3000L);
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
    void 并发冲突_插入时对方抢先则订正占位行并补发事件(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        repo.conflictOnNextInsert = true;   // 对方（LOAN_SETTLED 兜底）抢先提交
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        var view = service.registerRetention(42L, "VIN-42", false, TRACE);

        assertTrue(view.titleId() > 0);
        assertEquals("VIN-42", view.vin(), "占位行必须被订正为真实 VIN");
        assertEquals("REGISTERED", view.status());
        assertEquals(1, outbox.published.size(), "订正必须补发一次登记事件");
        assertEquals(OutboxPublisher.TITLE_RETENTION_REGISTERED, outbox.published.get(0).eventType());
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

    @Test
    void 兜底占位行_正式登记订正VIN并补发一次事件(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        service(repo, new RecordingOutbox(), tempDir).ensureRetentionForSettledLoan(50L);
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        var view = service.registerRetention(50L, "VIN-REAL-50", false, TRACE);

        assertEquals("VIN-REAL-50", view.vin(), "占位 VIN 必须被订正");
        assertEquals("REGISTERED", view.status());
        assertEquals(1, outbox.published.size(), "订正必须补发一次登记事件");
        assertEquals(OutboxPublisher.TITLE_RETENTION_REGISTERED, outbox.published.get(0).eventType());
        assertTrue(outbox.published.get(0).payload().contains("titleId"));
        assertTrue(!outbox.published.get(0).payload().contains("VIN-REAL-50"), "事件载荷不得带 VIN");
    }

    @Test
    void 订正后重复登记_幂等返回且不重复补发(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        service(repo, new RecordingOutbox(), tempDir).ensureRetentionForSettledLoan(51L);
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        service.registerRetention(51L, "VIN-REAL-51", false, TRACE);
        var again = service.registerRetention(51L, "VIN-REAL-51", false, TRACE);

        assertEquals("VIN-REAL-51", again.vin());
        assertEquals(1, outbox.published.size(), "已订正后重复登记不得重复补发事件");
    }

    @Test
    void 并发订正_对方先订正则本次幂等返回且不补发(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        service(repo, new RecordingOutbox(), tempDir).ensureRetentionForSettledLoan(52L);
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        // 调用方 A 先读到占位行（陈旧快照）
        repo.staleRowOnce = repo.byLoan.get(52L);
        // 调用方 B 先完成订正
        service.registerRetention(52L, "VIN-B-52", false, TRACE);
        assertEquals(1, outbox.published.size());

        // 调用方 A 随后用陈旧版本订正 → 影响 0 行 → 幂等返回，不补发
        var viewA = service.registerRetention(52L, "VIN-A-52", false, TRACE);

        assertEquals("VIN-B-52", viewA.vin());
        assertEquals(1, outbox.published.size(), "订正只发生一次，事件只补发一次");
    }

    @Test
    void 订正_已解押闭环的行被状态机拒绝(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        var service = service(repo, new RecordingOutbox(), tempDir);
        service.ensureRetentionForSettledLoan(53L);
        repo.forceStatus(53L, "RELEASE_REGISTERED");

        assertThrows(BusinessException.class,
                () -> service.registerRetention(53L, "VIN-53", false, TRACE));
    }

    @Test
    void 订正_跨态订正被状态机拒绝(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        service(repo, new RecordingOutbox(), tempDir).ensureRetentionForSettledLoan(54L);
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        // 占位行已按挡板登记为 REGISTERED；跨态订正（C-04 裁决口径仅 REGISTERED）一律拒绝
        assertThrows(BusinessException.class,
                () -> service.registerRetention(54L, "VIN-54", true, TRACE));

        assertEquals("UNKNOWN-54", repo.byLoan.get(54L).vin(), "被拒后不得改写占位行");
        assertTrue(outbox.published.isEmpty(), "被拒后不得补发事件");
    }

    @Test
    void 订正_真实登记行不被改写也不发事件(@TempDir Path tempDir) {
        var repo = new InMemoryTitleRepository();
        repo.insert(55L, "VIN-55", "REGISTERED", "REGISTERED", Timestamp.from(Instant.now()), null);
        var outbox = new RecordingOutbox();
        var service = service(repo, outbox, tempDir);

        var view = service.registerRetention(55L, "VIN-55", true, TRACE);

        assertEquals("VIN-55", view.vin());
        assertEquals("REGISTERED", view.status(), "真实登记行不得被改写为 CONTRACTUAL_ONLY");
        assertTrue(outbox.published.isEmpty(), "真实登记行重复登记不重复发事件");
    }
}
