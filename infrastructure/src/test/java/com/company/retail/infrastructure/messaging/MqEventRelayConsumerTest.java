package com.company.retail.infrastructure.messaging;

import com.company.platform.event.DomainEvent;
import com.company.platform.event.EventConsumer;
import com.company.retail.infrastructure.outbox.EventConsumptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0-4：MQ 中继未完成消费时必须抛错触发 MQ 重投，不能静默 ack。
 */
class MqEventRelayConsumerTest {

    private static final String PAYLOAD =
            "{\"eventId\":9,\"eventType\":\"TEST_EVENT\",\"aggregateKey\":\"K-9\","
                    + "\"payload\":\"{}\",\"occurredAt\":\"2026-11-24T00:00:00Z\"}";

    private static MqEventRelayConsumer relay(StubConsumption consumption) {
        return new MqEventRelayConsumer(consumption, List.of(new StubConsumer()));
    }

    @Test
    void resolvedEventDoesNotThrow() {
        StubConsumption consumption = new StubConsumption();
        consumption.result = true;

        assertDoesNotThrow(() -> relay(consumption).consume(null, PAYLOAD));
    }

    @Test
    void unresolvedEventThrowsToTriggerRedelivery() {
        StubConsumption consumption = new StubConsumption();
        consumption.result = false;

        assertThrows(IllegalStateException.class, () -> relay(consumption).consume(null, PAYLOAD));
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

        private boolean result;

        StubConsumption() {
            super(null);
        }

        @Override
        public boolean handle(EventConsumer consumer, DomainEvent event) {
            return result;
        }
    }
}
