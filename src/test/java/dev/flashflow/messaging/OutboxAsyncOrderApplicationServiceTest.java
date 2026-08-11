package dev.flashflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.flashflow.admission.AdmissionDecision;
import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.admission.AdmissionLifecycleDecision;
import dev.flashflow.admission.AdmissionLifecycleResult;
import dev.flashflow.admission.AdmissionPort;
import dev.flashflow.admission.AdmissionResult;
import dev.flashflow.messaging.outbox.DurableOutboxAcceptanceService;
import dev.flashflow.messaging.outbox.OutboxAsyncOrderApplicationService;
import dev.flashflow.messaging.persistence.CommandRow;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import dev.flashflow.shared.config.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OutboxAsyncOrderApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void durablePairReturnsAcceptedWithoutCallingBrokerAndReplaySkipsAdmission() {
        Fixture fixture = new Fixture();
        AsyncOrderSubmission first = fixture.service.submit(command(), "trace");

        assertThat(first.accepted()).isTrue();
        assertThat(first.status()).isEqualTo(CommandStatus.ACCEPTED);
        assertThat(first.publicationOutcome()).isEqualTo(PublicationOutcome.DURABLY_QUEUED);
        var ordered = inOrder(fixture.admission, fixture.acceptance);
        ordered.verify(fixture.admission).acquire(any());
        ordered.verify(fixture.acceptance).accept(any(), anyString(), anyString());

        when(fixture.ledger.find(first.commandId())).thenReturn(row(first.commandId(), "ACCEPTED"));
        AsyncOrderSubmission replay = fixture.service.submit(command(), "another-trace");
        assertThat(replay.commandId()).isEqualTo(first.commandId());
        verify(fixture.acceptance).replay(any(), anyString(), anyString());
        verify(fixture.admission).acquire(any());
    }

    @Test
    void definiteValidationFailureReleasesAndUnknownCommitFailureQuarantines() {
        Fixture definite = new Fixture();
        when(definite.acceptance.accept(any(), anyString(), anyString()))
                .thenThrow(new CommandConflictException("conflict"));
        AsyncOrderSubmission rejected = definite.service.submit(command(), "trace");
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.admissionResolution()).isEqualTo(PublicationResolution.RELEASED);
        verify(definite.admission).release(any(), anyString(), org.mockito.ArgumentMatchers.eq(false));

        Fixture ambiguous = new Fixture();
        when(ambiguous.acceptance.accept(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("commit response lost"));
        AsyncOrderSubmission unresolved = ambiguous.service.submit(command(), "trace");
        assertThat(unresolved.accepted()).isFalse();
        assertThat(unresolved.admissionResolution()).isEqualTo(PublicationResolution.QUARANTINED);
        verify(ambiguous.admission).quarantine(any(), anyString());
        verify(ambiguous.admission, never()).release(any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static PlaceOrderCommand command() {
        return new PlaceOrderCommand("user", "sku", "key");
    }

    private static CommandRow row(String id, String status) {
        java.time.LocalDateTime now = java.time.LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        return new CommandRow(id, "CREATE_ORDER", "user", "key", "sku", "b".repeat(64), 1,
                status, null, null, 0, 0, null, now, now, null, null);
    }

    private static final class Fixture {
        private final CommandLedgerService ledger = mock(CommandLedgerService.class);
        private final DurableOutboxAcceptanceService acceptance = mock(DurableOutboxAcceptanceService.class);
        private final AdmissionPort admission = mock(AdmissionPort.class);
        private final OutboxAsyncOrderApplicationService service;

        private Fixture() {
            FlashFlowProperties flash = flashProperties();
            AdmissionIdentity identities = new AdmissionIdentity(flash);
            when(admission.acquire(any())).thenAnswer(invocation -> {
                dev.flashflow.admission.AdmissionCommand command = invocation.getArgument(0);
                return new AdmissionResult(AdmissionDecision.ADMITTED, command.admissionId(), "g1");
            });
            when(admission.release(any(), anyString(), org.mockito.ArgumentMatchers.eq(false)))
                    .thenReturn(new AdmissionLifecycleResult(AdmissionLifecycleDecision.RELEASED, "g1"));
            when(admission.quarantine(any(), anyString()))
                    .thenReturn(new AdmissionLifecycleResult(AdmissionLifecycleDecision.QUARANTINED, "g1"));
            service = new OutboxAsyncOrderApplicationService(new OrderCommandFactory(identities, CLOCK), ledger,
                    acceptance, admission, identities, flash, messagingProperties(),
                    new FlashFlowMetrics(new SimpleMeterRegistry()), CLOCK);
        }
    }

    private static FlashFlowProperties flashProperties() {
        return new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 3),
                new FlashFlowProperties.Ordering(3, 1, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 100, true),
                new FlashFlowProperties.Admission(FlashFlowProperties.AdmissionMode.REDIS_LUA,
                        Duration.ofSeconds(30), "v4", "01234567890123456789012345678901"));
    }

    private static MessagingProperties messagingProperties() {
        return new MessagingProperties(MessagingProperties.Mode.DIRECT, "127.0.0.1:9876", "5.3.3", "5.3.4",
                "producer", "orders", "orders-group", "expiration", "expiration-group", "dead-letter",
                Duration.ofSeconds(3), 1, 3, 14, Duration.ofSeconds(30),
                MessagingProperties.ConsumeStart.FIRST, MessagingProperties.Acknowledgement.SYNC_FLUSH,
                new MessagingProperties.Outbox(50, Duration.ofMillis(250), Duration.ofSeconds(10), 8,
                        Duration.ofMillis(500), Duration.ofSeconds(30), "test", true, false,
                        Duration.ofDays(7)));
    }
}
