package dev.flashflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

class RocketMqListenerContractTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void acknowledgesRecoverableResultAndDeadLettersPoisonOrExhaustedDelivery() throws Exception {
        AtomicInteger deadLetters = new AtomicInteger();
        DeadLetterPublisher dlq = (topic, key, version, attempts, reason, body) -> {
            deadLetters.incrementAndGet();
            return true;
        };
        CommandLedgerService ledger = new CommandLedgerService(new InMemoryCommandMapper(),
                Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC));
        RocketMqOrderCommandConsumer acknowledged = new RocketMqOrderCommandConsumer(properties(), JSON,
                envelope -> new CommandConsumptionResult(envelope.commandId(), CommandStatus.COMPLETED,
                        "CREATED", "order-1", ConsumerOutcome.ACKNOWLEDGED, true),
                dlq, ledger, new FlashFlowMetrics(new SimpleMeterRegistry()), new MessagingFaultInjector("NONE"));

        assertThat(acknowledged.consumeMessage(List.of(message(validBody(), 0)), null))
                .isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(acknowledged.consumeMessage(List.of(message(new byte[]{1, 2, 3}, 0)), null))
                .isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);

        RocketMqOrderCommandConsumer retrying = new RocketMqOrderCommandConsumer(properties(), JSON,
                envelope -> new CommandConsumptionResult(envelope.commandId(), CommandStatus.RETRYABLE,
                        null, null, ConsumerOutcome.RETRY, false), dlq, ledger,
                new FlashFlowMetrics(new SimpleMeterRegistry()), new MessagingFaultInjector("NONE"));
        assertThat(retrying.consumeMessage(List.of(message(validBody(), 0)), null))
                .isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
        assertThat(retrying.consumeMessage(List.of(message(validBody(), 3)), null))
                .isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        assertThat(deadLetters).hasValue(2);
    }

    private static byte[] validBody() throws Exception {
        return JSON.writeValueAsBytes(new OrderCommandEnvelope(1, "a".repeat(64), "user", "sku", "key",
                "b".repeat(64), Instant.parse("2026-08-10T10:00:00Z"), "trace"));
    }

    private static MessageExt message(byte[] body, int attempts) {
        MessageExt message = new MessageExt();
        message.setTopic("orders");
        message.setKeys("a".repeat(64));
        message.setBody(body);
        message.setReconsumeTimes(attempts);
        return message;
    }

    private static MessagingProperties properties() {
        return new MessagingProperties(MessagingProperties.Mode.DIRECT, "127.0.0.1:9876", "5.3.3", "5.3.4",
                "producer", "orders", "orders-group", "expiration", "expiration-group", "dead-letter",
                Duration.ofSeconds(3), 1, 3, 14, Duration.ofSeconds(30),
                MessagingProperties.ConsumeStart.FIRST,
                MessagingProperties.Acknowledgement.SYNC_FLUSH, outbox());
    }

    private static MessagingProperties.Outbox outbox() {
        return new MessagingProperties.Outbox(50, Duration.ofMillis(250), Duration.ofSeconds(10),
                8, Duration.ofMillis(500), Duration.ofSeconds(30), "test", true, false,
                Duration.ofDays(7));
    }
}
