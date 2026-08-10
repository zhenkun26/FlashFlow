package dev.flashflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.ordering.OrderResult;
import dev.flashflow.ordering.OrderResultCode;
import dev.flashflow.ordering.OrderStatus;
import dev.flashflow.shared.FlashFlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InProcessOrderCommandConsumerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void duplicateAndLostAcknowledgementReplayOneCommittedResult() {
        CommandLedgerService ledger = new CommandLedgerService(new InMemoryCommandMapper(), CLOCK);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger expirations = new AtomicInteger();
        OrderCommandExecutor executor = command -> {
            executions.incrementAndGet();
            return new OrderResult(OrderResultCode.CREATED, "order-1", OrderStatus.PENDING_PAYMENT,
                    LocalDateTime.of(2026, 8, 10, 10, 10), "created");
        };
        InProcessOrderCommandConsumer consumer = new InProcessOrderCommandConsumer(ledger, executor,
                new FlashFlowMetrics(new SimpleMeterRegistry()), envelope -> {
                    expirations.incrementAndGet();
                    return new PublicationResult(PublicationOutcome.BROKER_ACKNOWLEDGED, "SEND_OK");
                });

        CommandConsumptionResult first = consumer.consume(envelope());
        CommandConsumptionResult duplicate = consumer.consume(envelope());

        assertThat(first.acknowledgementEligible()).isTrue();
        assertThat(duplicate.status()).isEqualTo(CommandStatus.COMPLETED);
        assertThat(executions).hasValue(1);
        assertThat(expirations).hasValue(1);
    }

    @Test
    void interruptionBeforeCommitLeavesRetryableIdentityAndRedeliveryCompletes() {
        CommandLedgerService ledger = new CommandLedgerService(new InMemoryCommandMapper(), CLOCK);
        AtomicInteger attempts = new AtomicInteger();
        OrderCommandExecutor executor = command -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("before commit");
            return OrderResult.rejected(OrderResultCode.SOLD_OUT, "sold out");
        };
        InProcessOrderCommandConsumer consumer = new InProcessOrderCommandConsumer(ledger, executor,
                new FlashFlowMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> consumer.consume(envelope())).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.summary(envelope().commandId()).status()).isEqualTo(CommandStatus.UNRESOLVED);
        assertThat(consumer.consume(envelope()).status()).isEqualTo(CommandStatus.REJECTED);
        assertThat(attempts).hasValue(2);
    }

    private static OrderCommandEnvelope envelope() {
        return new OrderCommandEnvelope(1, "a".repeat(64), "user", "sku", "key", "b".repeat(64),
                Instant.parse("2026-08-10T10:00:00Z"), "trace");
    }
}
