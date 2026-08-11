package dev.flashflow.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionDecision;
import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.admission.AdmissionLifecycleDecision;
import dev.flashflow.admission.AdmissionLifecycleResult;
import dev.flashflow.admission.AdmissionPort;
import dev.flashflow.admission.AdmissionResult;
import dev.flashflow.messaging.persistence.CommandMapper;
import dev.flashflow.messaging.persistence.CommandRow;
import dev.flashflow.messaging.web.AsyncOrderController;
import dev.flashflow.messaging.web.AsyncOrderResponse;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.ordering.web.PlaceOrderRequest;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AsyncOrderApplicationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void brokerAcknowledgementIsTheOnlyPathTo202AndReplayDoesNotRepublish() {
        Fixture fixture = new Fixture(new PublicationResult(PublicationOutcome.BROKER_ACKNOWLEDGED, "SEND_OK"));
        AsyncOrderController controller = new AsyncOrderController(fixture.service, fixture.ledger);

        var first = controller.place("key-1", new PlaceOrderRequest("user-1", "sku-1"));
        var replay = controller.place("key-1", new PlaceOrderRequest("user-1", "sku-1"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getHeaders().getLocation()).isEqualTo(first.getBody().statusLocation());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(fixture.publishCount).isEqualTo(1);
        assertThat(fixture.admission.lastLifecycle).isNull();
        AsyncOrderResponse body = first.getBody();
        assertThat(controller.status(body.commandId(), "user-1").getBody().status())
                .isEqualTo(CommandStatus.ACCEPTED);
        assertThat(controller.status(body.commandId(), "another-user").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void durableReplayStatesRemainAcceptedWhileUnacceptedStatesReturn503() {
        for (CommandStatus status : java.util.List.of(
                CommandStatus.ACCEPTED, CommandStatus.PROCESSING,
                CommandStatus.COMPLETED, CommandStatus.REJECTED)) {
            assertThat(submission(status).accepted()).as(status.name()).isTrue();
        }
        for (CommandStatus status : java.util.List.of(
                CommandStatus.PREPARED, CommandStatus.RETRYABLE, CommandStatus.UNRESOLVED)) {
            assertThat(submission(status).accepted()).as(status.name()).isFalse();
        }
    }

    @Test
    void definitiveFailureReleasesWhileAmbiguityQuarantinesAndBothReturn503() {
        Fixture definitive = new Fixture(new PublicationResult(
                PublicationOutcome.DEFINITELY_NOT_PUBLISHED, "CONNECT_FAILED"));
        var failed = new AsyncOrderController(definitive.service, definitive.ledger)
                .place("key-2", new PlaceOrderRequest("user-2", "sku-1"));
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failed.getBody().status()).isEqualTo(CommandStatus.RETRYABLE);
        assertThat(definitive.admission.lastLifecycle).isEqualTo(AdmissionLifecycleDecision.RELEASED);

        Fixture ambiguous = new Fixture(new PublicationResult(PublicationOutcome.AMBIGUOUS, "REMOTING_TIMEOUT"));
        var unresolved = new AsyncOrderController(ambiguous.service, ambiguous.ledger)
                .place("key-3", new PlaceOrderRequest("user-3", "sku-1"));
        assertThat(unresolved.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unresolved.getBody().status()).isEqualTo(CommandStatus.UNRESOLVED);
        assertThat(ambiguous.admission.lastLifecycle).isEqualTo(AdmissionLifecycleDecision.QUARANTINED);

        var retry = new AsyncOrderController(ambiguous.service, ambiguous.ledger)
                .place("key-3", new PlaceOrderRequest("user-3", "sku-1"));
        assertThat(retry.getBody().commandId()).isEqualTo(unresolved.getBody().commandId());
        assertThat(ambiguous.publishCount).isEqualTo(2);
    }

    private static final class Fixture {
        private final InMemoryCommandMapper mapper = new InMemoryCommandMapper();
        private final CommandLedgerService ledger = new CommandLedgerService(mapper, CLOCK);
        private final FakeAdmission admission = new FakeAdmission();
        private int publishCount;
        private final AsyncOrderApplicationService service;

        private Fixture(PublicationResult publication) {
            FlashFlowProperties properties = properties();
            AdmissionIdentity identities = new AdmissionIdentity(properties);
            FlashFlowMetrics metrics = new FlashFlowMetrics(new SimpleMeterRegistry());
            service = new AsyncOrderApplicationService(new OrderCommandFactory(identities, CLOCK), ledger,
                    admission, identities, properties,
                    new DeterministicPublicationCoordinator(admission, metrics), envelope -> {
                        publishCount++;
                        return publication;
                    }, metrics, CLOCK);
        }
    }

    private static FlashFlowProperties properties() {
        return new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 3),
                new FlashFlowProperties.Ordering(3, 1, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 100, true),
                new FlashFlowProperties.Admission(FlashFlowProperties.AdmissionMode.REDIS_LUA,
                        Duration.ofSeconds(30), "v2-1", "01234567890123456789012345678901"));
    }

    private static AsyncOrderSubmission submission(CommandStatus status) {
        return new AsyncOrderSubmission("command", status, java.net.URI.create("/status"),
                PublicationOutcome.BROKER_ACKNOWLEDGED, PublicationResolution.RETAINED, "DURABLE_REPLAY");
    }

    private static final class FakeAdmission implements AdmissionPort {
        private AdmissionLifecycleDecision lastLifecycle;

        @Override
        public AdmissionResult acquire(AdmissionCommand command) {
            return new AdmissionResult(AdmissionDecision.ADMITTED, command.admissionId(), "g1");
        }

        @Override
        public AdmissionLifecycleResult confirm(AdmissionCommand command, String generation) {
            lastLifecycle = AdmissionLifecycleDecision.CONFIRMED;
            return new AdmissionLifecycleResult(lastLifecycle, generation);
        }

        @Override
        public AdmissionLifecycleResult release(AdmissionCommand command, String generation, boolean closure) {
            lastLifecycle = AdmissionLifecycleDecision.RELEASED;
            return new AdmissionLifecycleResult(lastLifecycle, generation);
        }

        @Override
        public AdmissionLifecycleResult quarantine(AdmissionCommand command, String generation) {
            lastLifecycle = AdmissionLifecycleDecision.QUARANTINED;
            return new AdmissionLifecycleResult(lastLifecycle, generation);
        }
    }

    private static final class InMemoryCommandMapper implements CommandMapper {
        private final Map<String, CommandRow> rows = new LinkedHashMap<>();

        @Override
        public int tryInsert(String id, String caller, String key, String sku, String fingerprint,
                             int version, LocalDateTime now) {
            if (rows.containsKey(id)) return 0;
            rows.put(id, new CommandRow(id, "CREATE_ORDER", caller, key, sku, fingerprint, version,
                    "PREPARED", null, null, 0, 0, null, now, now, null, null));
            return 1;
        }

        @Override public CommandRow findById(String id) { return rows.get(id); }

        @Override
        public CommandRow findByIdAndCaller(String id, String caller) {
            CommandRow row = rows.get(id);
            return row != null && row.callerId().equals(caller) ? row : null;
        }

        @Override public int claim(String id, LocalDateTime now) { return 0; }
        @Override public int finish(String id, String status, String code, String orderId, LocalDateTime now) { return 0; }

        @Override
        public int markNonTerminal(String id, String status, String cause, int publicationAttempt, LocalDateTime now) {
            CommandRow row = rows.get(id);
            if (row == null) return 0;
            rows.put(id, new CommandRow(row.commandId(), row.operationName(), row.callerId(),
                    row.idempotencyKey(), row.activitySkuId(), row.payloadFingerprint(), row.schemaVersion(),
                    status, row.resultCode(), row.orderId(), row.attemptCount(),
                    row.publicationAttemptCount() + publicationAttempt, cause,
                    row.createdAt(), now, row.completedAt(), row.deadLetteredAt()));
            return 1;
        }

        @Override public int markDeadLetter(String id, String cause, LocalDateTime now) { return 0; }
    }
}
