package dev.flashflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.flashflow.messaging.outbox.ClaimedOutbox;
import dev.flashflow.messaging.outbox.OutboxDispatcher;
import dev.flashflow.messaging.outbox.OutboxStatus;
import dev.flashflow.messaging.outbox.OutboxStore;
import dev.flashflow.messaging.persistence.OutboxRow;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OutboxDispatcherTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void sendOkRecordsAcknowledgementForCurrentLease() {
        Fixture fixture = new Fixture(8);
        OutboxRow claimed = row(1, "orders", "ORDER_V1");
        when(fixture.store.claimBatch(any(Integer.class), anyString(), any(Duration.class)))
                .thenReturn(List.of(new ClaimedOutbox(claimed, false)));
        when(fixture.store.envelope(claimed)).thenReturn(envelope());
        when(fixture.publisher.publish(any())).thenReturn(
                new PublicationResult(PublicationOutcome.BROKER_ACKNOWLEDGED, "SEND_OK"));
        when(fixture.store.acknowledge(claimed, "SEND_OK")).thenReturn(true);

        assertThat(fixture.dispatcher.dispatchBatch()).isEqualTo(1);
        verify(fixture.store).acknowledge(claimed, "SEND_OK");
        verify(fixture.store, never()).retry(any(), anyString(), any());
    }

    @Test
    void ambiguousFailureSchedulesBoundedBackoffAndExhaustionStops() {
        Fixture retry = new Fixture(8);
        OutboxRow first = row(2, "orders", "ORDER_V1");
        when(retry.store.claimBatch(any(Integer.class), anyString(), any(Duration.class)))
                .thenReturn(List.of(new ClaimedOutbox(first, true)));
        when(retry.store.envelope(first)).thenReturn(envelope());
        when(retry.publisher.publish(any())).thenReturn(
                new PublicationResult(PublicationOutcome.AMBIGUOUS, "REMOTING_TIMEOUT"));
        when(retry.store.retry(any(), anyString(), any())).thenReturn(true);
        retry.dispatcher.dispatchBatch();
        ArgumentCaptor<LocalDateTime> due = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(retry.store).retry(eq(first), eq("REMOTING_TIMEOUT"), due.capture());
        assertThat(due.getValue()).isEqualTo(LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC)
                .plusSeconds(1));

        Fixture exhausted = new Fixture(2);
        OutboxRow last = row(2, "orders", "ORDER_V1");
        when(exhausted.store.claimBatch(any(Integer.class), anyString(), any(Duration.class)))
                .thenReturn(List.of(new ClaimedOutbox(last, false)));
        when(exhausted.store.envelope(last)).thenReturn(envelope());
        when(exhausted.publisher.publish(any())).thenReturn(
                new PublicationResult(PublicationOutcome.DEFINITELY_NOT_PUBLISHED, "CONNECT_FAILED"));
        exhausted.dispatcher.dispatchBatch();
        verify(exhausted.store).stop(last, OutboxStatus.EXHAUSTED, "CONNECT_FAILED");
    }

    @Test
    void invalidRoutingIsNotPublishedAndEmptyPollDoesNothing() {
        Fixture invalid = new Fixture(8);
        OutboxRow row = row(1, "wrong-topic", "ORDER_V1");
        when(invalid.store.claimBatch(any(Integer.class), anyString(), any(Duration.class)))
                .thenReturn(List.of(new ClaimedOutbox(row, false)));
        invalid.dispatcher.dispatchBatch();
        verify(invalid.store).stop(row, OutboxStatus.INVALID, "Outbox routing metadata mismatch");
        verify(invalid.publisher, never()).publish(any());

        Fixture empty = new Fixture(8);
        when(empty.store.claimBatch(any(Integer.class), anyString(), any(Duration.class))).thenReturn(List.of());
        assertThat(empty.dispatcher.dispatchBatch()).isZero();
        verify(empty.publisher, never()).publish(any());
    }

    private static OutboxRow row(int attempts, String topic, String tag) {
        LocalDateTime now = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        return new OutboxRow("outbox", "a".repeat(64), 1, "payload", "b".repeat(64), topic, tag,
                "CLAIMED", attempts, now, "lease", "owner", now.plusSeconds(10), null, null, now, now);
    }

    private static OrderCommandEnvelope envelope() {
        return new OrderCommandEnvelope(1, "a".repeat(64), "user", "sku", "key", "b".repeat(64),
                CLOCK.instant(), "trace");
    }

    private static final class Fixture {
        private final OutboxStore store = mock(OutboxStore.class);
        private final OrderCommandPublisher publisher = mock(OrderCommandPublisher.class);
        private final OutboxDispatcher dispatcher;

        private Fixture(int maxAttempts) {
            dispatcher = new OutboxDispatcher(store, publisher, properties(maxAttempts),
                    new FlashFlowMetrics(new SimpleMeterRegistry()), new MessagingFaultInjector("NONE"), CLOCK);
        }
    }

    private static MessagingProperties properties(int maxAttempts) {
        return new MessagingProperties(MessagingProperties.Mode.OUTBOX, "127.0.0.1:9876", "5.3.3", "5.3.4",
                "producer", "orders", "orders-group", "expiration", "expiration-group", "dead-letter",
                Duration.ofSeconds(3), 1, 3, 14, Duration.ofSeconds(30),
                MessagingProperties.ConsumeStart.FIRST, MessagingProperties.Acknowledgement.SYNC_FLUSH,
                new MessagingProperties.Outbox(50, Duration.ofMillis(250), Duration.ofSeconds(10), maxAttempts,
                        Duration.ofMillis(500), Duration.ofSeconds(30), "test", true, false,
                        Duration.ofDays(7)));
    }
}
