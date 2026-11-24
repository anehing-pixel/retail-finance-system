package com.company.retail.infrastructure.outbox;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.infrastructure.config.MqProperties;
import com.company.retail.infrastructure.mapper.OutboxMapper;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-4：直投轮询按「已终态才推进游标」运行；MQ 中继接管时直投让位。
 */
class RfEventDispatcherTest {

    private final FakeMapper mapper = new FakeMapper();
    private final StubConsumption consumption = new StubConsumption();
    private final StubConsumer consumer = new StubConsumer();

    private RfEventDispatcher dispatcher(MqProperties mqProperties) {
        return new RfEventDispatcher(mapper, List.of(consumer), consumption, mqProperties);
    }

    private static Map<String, Object> row(long eventId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("event_type", "TEST_EVENT");
        row.put("aggregate_key", "K-" + eventId);
        row.put("payload", "{}");
        row.put("created_at", Timestamp.from(Instant.now()));
        return row;
    }

    @Test
    void advancesCursorOnlyForTerminalEvents() {
        mapper.rows.add(row(1));
        mapper.rows.add(row(2));
        mapper.rows.add(row(3));
        consumption.results.addAll(List.of(true, false, true));

        dispatcher(null).poll();

        assertEquals(List.of(1L), mapper.writes);
        assertEquals(List.of(1L), mapper.sent);
        assertEquals(2, consumption.calls);
    }

    @Test
    void advancesAllWhenAllTerminal() {
        mapper.rows.add(row(1));
        mapper.rows.add(row(2));
        consumption.results.addAll(List.of(true, true));

        dispatcher(null).poll();

        assertEquals(List.of(1L, 2L), mapper.writes);
        assertEquals(List.of(1L, 2L), mapper.sent);
    }

    @Test
    void relayOwnedConsumerIsSkippedByDirectDelivery() {
        mapper.rows.add(row(1));
        MqProperties mq = new MqProperties();
        mq.setRelayConsumerEnabled(true);
        mq.setDirectConsumers(List.of());

        dispatcher(mq).poll();

        assertEquals(0, mapper.selectCalls);
        assertTrue(mapper.writes.isEmpty());
    }

    @Test
    void consumerExceptionDoesNotBreakPoll() {
        mapper.rows.add(row(1));
        consumption.failure = new IllegalStateException("boom");

        dispatcher(null).poll();

        assertTrue(mapper.writes.isEmpty());
        assertEquals(0, mapper.sent.size());
    }

    static final class StubConsumer implements EventConsumer {

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
        }
    }

    static final class StubConsumption extends EventConsumptionService {

        private final Deque<Boolean> results = new ArrayDeque<>();
        private int calls;
        private RuntimeException failure;

        StubConsumption() {
            super(null);
        }

        @Override
        public boolean handle(EventConsumer consumer, DomainEvent event) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            Boolean result = results.pollFirst();
            return result != null && result;
        }
    }

    static final class FakeMapper implements OutboxMapper {

        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final List<Long> writes = new ArrayList<>();
        private final List<Long> sent = new ArrayList<>();
        private int selectCalls;
        private long offset;

        @Override
        public int insertEventIfAbsent(String eventType, String aggregateKey, String payload) {
            return 1;
        }

        @Override
        public List<Map<String, Object>> selectEventsAfter(long offset, Collection<String> types, int limit) {
            selectCalls++;
            return new ArrayList<>(rows);
        }

        @Override
        public int markSent(long eventId) {
            sent.add(eventId);
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
            return List.of();
        }

        @Override
        public List<Map<String, Object>> claimConsumption(long eventId, String consumer,
                                                          long leaseSeconds, int maxAttempts) {
            return List.of();
        }

        @Override
        public int markConsumptionDone(long eventId, String consumer, int next) {
            return 1;
        }

        @Override
        public int markConsumptionFailed(long eventId, String consumer, int next, String err) {
            return 1;
        }

        @Override
        public int markConsumptionDead(long eventId, String consumer, String err) {
            return 1;
        }

        @Override
        public int markConsumptionDeadIfExhausted(long eventId, String consumer, String err,
                                                  int maxAttempts) {
            return 1;
        }

        @Override
        public int insertOffsetIfAbsent(String consumer) {
            return 1;
        }

        @Override
        public Long findOffset(String consumer) {
            return offset;
        }

        @Override
        public int writeOffset(String consumer, long eventId) {
            writes.add(eventId);
            offset = Math.max(offset, eventId);
            return 1;
        }
    }
}
