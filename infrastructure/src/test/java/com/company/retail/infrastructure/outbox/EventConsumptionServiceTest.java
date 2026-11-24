package com.company.retail.infrastructure.outbox;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.infrastructure.mapper.OutboxMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-4：多实例事件消费——原子认领保证同一 (event, consumer) 只有一个实例执行。
 */
class EventConsumptionServiceTest {

    private final FakeMapper mapper = new FakeMapper();
    private final RecordingConsumer consumer = new RecordingConsumer();

    private EventConsumptionService service() {
        return new EventConsumptionService(mapper);
    }

    private static DomainEvent event() {
        return new DomainEvent(1L, "TEST_EVENT", "K-1", "{}", Instant.now());
    }

    @Test
    void doneEventSkipsWithoutClaim() {
        mapper.findResults.add(Map.of("status", "DONE", "attempts", 1));

        assertTrue(service().handle(consumer, event()));
        assertEquals(0, mapper.claimCalls);
        assertEquals(0, consumer.calls.get());
    }

    @Test
    void failedClaimReturnsFalseAndDoesNotExecute() {
        mapper.findResults.add(Map.of("status", "PENDING", "attempts", 0));
        mapper.findResults.add(Map.of("status", "PROCESSING", "attempts", 0));

        assertFalse(service().handle(consumer, event()));
        assertEquals(1, mapper.claimCalls);
        assertEquals(0, consumer.calls.get(), "未抢到认领不得执行");
    }

    @Test
    void failedClaimButTerminalReturnsTrue() {
        mapper.findResults.add(Map.of("status", "PENDING", "attempts", 0));
        mapper.findResults.add(Map.of("status", "DONE", "attempts", 1));

        assertTrue(service().handle(consumer, event()));
        assertEquals(0, consumer.calls.get());
    }

    @Test
    void successfulClaimExecutesAndMarksDone() {
        mapper.findResults.add(Map.of("status", "PENDING", "attempts", 0));
        mapper.claimResults.add(List.of(Map.of("attempts", 0)));

        assertTrue(service().handle(consumer, event()));
        assertEquals(1, consumer.calls.get());
        assertEquals(1, mapper.markDoneCalls);
        assertEquals(1, mapper.lastNext);
    }

    @Test
    void failureMarksFailedAndReturnsFalse() {
        mapper.findResults.add(Map.of("status", "PENDING", "attempts", 0));
        mapper.claimResults.add(List.of(Map.of("attempts", 0)));
        consumer.failure = new IllegalStateException("boom");

        assertFalse(service().handle(consumer, event()));
        assertEquals(1, mapper.markFailedCalls);
        assertEquals(1, mapper.lastNext);
    }

    @Test
    void thirdFailureMarksDeadAndReturnsTrue() {
        mapper.findResults.add(Map.of("status", "FAILED", "attempts", 2));
        mapper.claimResults.add(List.of(Map.of("attempts", 2)));
        consumer.failure = new IllegalStateException("boom");

        assertTrue(service().handle(consumer, event()));
        assertEquals(1, mapper.markDeadCalls);
    }

    @Test
    void exhaustedAttemptsMarksDeadWithoutExecution() {
        mapper.findResults.add(Map.of("status", "FAILED", "attempts", 3));

        assertTrue(service().handle(consumer, event()));
        assertEquals(0, mapper.claimCalls);
        assertEquals(0, consumer.calls.get());
        assertEquals(1, mapper.markDeadIfExhaustedCalls);
    }

    @Test
    void onlyOneOfTwoInstancesWinsTheClaim() {
        mapper.findResults.add(Map.of("status", "PENDING", "attempts", 0));
        mapper.findResults.add(Map.of("status", "PENDING", "attempts", 0));
        mapper.findOneClaimOnly = true;

        EventConsumptionService first = new EventConsumptionService(mapper);
        EventConsumptionService second = new EventConsumptionService(mapper);

        assertTrue(first.handle(consumer, event()));
        assertFalse(second.handle(consumer, event()));
        assertEquals(1, consumer.calls.get(), "同一事件只能被一个实例执行");
    }

    static final class RecordingConsumer implements EventConsumer {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile RuntimeException failure;

        @Override
        public String name() {
            return "test-consumer";
        }

        @Override
        public Set<String> eventTypes() {
            return Set.of("TEST_EVENT");
        }

        @Override
        public void consume(DomainEvent event) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
        }
    }

    static final class FakeMapper implements OutboxMapper {

        private final Deque<Map<String, Object>> findResults = new ArrayDeque<>();
        private final Deque<List<Map<String, Object>>> claimResults = new ArrayDeque<>();
        private final AtomicBoolean claimedOnce = new AtomicBoolean();
        private boolean findOneClaimOnly;
        private int claimCalls;
        private int markDoneCalls;
        private int markFailedCalls;
        private int markDeadCalls;
        private int markDeadIfExhaustedCalls;
        private int lastNext;

        @Override
        public int insertEventIfAbsent(String eventType, String aggregateKey, String payload) {
            return 1;
        }

        @Override
        public List<Map<String, Object>> selectEventsAfter(long offset, Collection<String> types, int limit) {
            return List.of();
        }

        @Override
        public int markSent(long eventId) {
            return 1;
        }

        @Override
        public List<Map<String, Object>> selectUnrelayedEvents(int limit) {
            return List.of();
        }

        @Override
        public int markRelayed(long eventId) {
            return 1;
        }

        @Override
        public int insertConsumptionIfAbsent(long eventId, String consumer) {
            return 1;
        }

        @Override
        public List<Map<String, Object>> findConsumption(long eventId, String consumer) {
            Map<String, Object> next = findResults.pollFirst();
            return next == null ? List.of() : List.of(new HashMap<>(next));
        }

        @Override
        public List<Map<String, Object>> claimConsumption(long eventId, String consumer,
                                                          long leaseSeconds, int maxAttempts) {
            claimCalls++;
            if (findOneClaimOnly) {
                return claimedOnce.compareAndSet(false, true)
                        ? List.of(Map.of("attempts", 0)) : List.of();
            }
            return claimResults.isEmpty() ? List.of() : claimResults.pollFirst();
        }

        @Override
        public int markConsumptionDone(long eventId, String consumer, int next) {
            markDoneCalls++;
            lastNext = next;
            return 1;
        }

        @Override
        public int markConsumptionFailed(long eventId, String consumer, int next, String err) {
            markFailedCalls++;
            lastNext = next;
            return 1;
        }

        @Override
        public int markConsumptionDead(long eventId, String consumer, String err) {
            markDeadCalls++;
            return 1;
        }

        @Override
        public int markConsumptionDeadIfExhausted(long eventId, String consumer, String err,
                                                  int maxAttempts) {
            markDeadIfExhaustedCalls++;
            return 1;
        }

        @Override
        public int insertOffsetIfAbsent(String consumer) {
            return 1;
        }

        @Override
        public Long findOffset(String consumer) {
            return 0L;
        }

        @Override
        public int writeOffset(String consumer, long eventId) {
            return 1;
        }
    }
}
