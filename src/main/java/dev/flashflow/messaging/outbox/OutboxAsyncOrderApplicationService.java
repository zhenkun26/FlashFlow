package dev.flashflow.messaging.outbox;

import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.admission.AdmissionPort;
import dev.flashflow.admission.AdmissionResult;
import dev.flashflow.messaging.AsyncOrderSubmission;
import dev.flashflow.messaging.AsyncOrderSubmissionService;
import dev.flashflow.messaging.CommandLedgerService;
import dev.flashflow.messaging.CommandConflictException;
import dev.flashflow.messaging.CommandStatus;
import dev.flashflow.messaging.OrderCommandEnvelope;
import dev.flashflow.messaging.OrderCommandFactory;
import dev.flashflow.messaging.PublicationOutcome;
import dev.flashflow.messaging.PublicationResolution;
import dev.flashflow.messaging.persistence.CommandRow;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import dev.flashflow.shared.config.MessagingProperties;
import java.net.URI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "flashflow.messaging", name = "mode", havingValue = "OUTBOX")
public final class OutboxAsyncOrderApplicationService implements AsyncOrderSubmissionService {
    private static final Logger log = LoggerFactory.getLogger(OutboxAsyncOrderApplicationService.class);
    private static final String ORDER_TAG = "ORDER_V1";
    private final OrderCommandFactory commands;
    private final CommandLedgerService ledger;
    private final DurableOutboxAcceptanceService acceptance;
    private final AdmissionPort admission;
    private final AdmissionIdentity identities;
    private final FlashFlowProperties properties;
    private final MessagingProperties messaging;
    private final FlashFlowMetrics metrics;
    private final Clock clock;

    public OutboxAsyncOrderApplicationService(
            OrderCommandFactory commands, CommandLedgerService ledger,
            DurableOutboxAcceptanceService acceptance, AdmissionPort admission,
            AdmissionIdentity identities, FlashFlowProperties properties, MessagingProperties messaging,
            FlashFlowMetrics metrics, Clock clock) {
        this.commands = commands;
        this.ledger = ledger;
        this.acceptance = acceptance;
        this.admission = admission;
        this.identities = identities;
        this.properties = properties;
        this.messaging = messaging;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public AsyncOrderSubmission submit(PlaceOrderCommand command, String traceId) {
        OrderCommandEnvelope envelope = commands.create(command, traceId);
        CommandRow existing = ledger.find(envelope.commandId());
        if (existing != null && acceptedOrLater(existing.status())) {
            acceptance.replay(envelope, messaging.orderTopic(), ORDER_TAG);
            return accepted(envelope.commandId(), CommandStatus.valueOf(existing.status()), "DURABLE_REPLAY");
        }

        AdmissionCommand admissionCommand = new AdmissionCommand(command.activitySkuId(), envelope.commandId(),
                identities.userDigest(command.activitySkuId(), command.userId()),
                clock.instant().plus(properties.admission().heldResolution()));
        AdmissionResult admitted = admission.acquire(admissionCommand);
        metrics.admissionDecision(admitted.decision().name());
        if (!admitted.permitsMySqlAttempt()) {
            return retryable(envelope.commandId(), "ADMISSION_" + admitted.decision().name(),
                    PublicationResolution.RELEASED);
        }

        try {
            acceptance.accept(envelope, messaging.orderTopic(), ORDER_TAG);
            metrics.command("OUTBOX_ACCEPTANCE", "ACCEPTED");
            log.info("Durable Outbox acceptance command_id={} status=ACCEPTED", envelope.commandId());
            return accepted(envelope.commandId(), CommandStatus.ACCEPTED, "OUTBOX_COMMITTED");
        } catch (CommandConflictException | IllegalArgumentException definiteFailure) {
            PublicationResolution resolution = release(admissionCommand, admitted.generation());
            metrics.command("OUTBOX_ACCEPTANCE", "REJECTED");
            return retryable(envelope.commandId(), "OUTBOX_DEFINITELY_NOT_ACCEPTED", resolution);
        } catch (RuntimeException failure) {
            PublicationResolution resolution = quarantine(admissionCommand, admitted.generation());
            metrics.command("OUTBOX_ACCEPTANCE", "UNRESOLVED");
            log.warn("Durable Outbox acceptance unresolved command_id={} cause={}",
                    envelope.commandId(), failure.getClass().getSimpleName());
            return retryable(envelope.commandId(), "OUTBOX_COMMIT_UNRESOLVED", resolution);
        }
    }

    private PublicationResolution release(AdmissionCommand command, String generation) {
        try {
            var result = admission.release(command, generation, false);
            return switch (result.decision()) {
                case RELEASED, ALREADY_RELEASED -> PublicationResolution.RELEASED;
                default -> quarantine(command, generation);
            };
        } catch (RuntimeException unavailable) {
            return quarantine(command, generation);
        }
    }

    private PublicationResolution quarantine(AdmissionCommand command, String generation) {
        try {
            admission.quarantine(command, generation);
            return PublicationResolution.QUARANTINED;
        } catch (RuntimeException unavailable) {
            return PublicationResolution.UNRESOLVED;
        }
    }

    private static boolean acceptedOrLater(String status) {
        return status.equals(CommandStatus.ACCEPTED.name()) || status.equals(CommandStatus.PROCESSING.name())
                || status.equals(CommandStatus.COMPLETED.name()) || status.equals(CommandStatus.REJECTED.name());
    }

    private static AsyncOrderSubmission accepted(String commandId, CommandStatus status, String cause) {
        return new AsyncOrderSubmission(commandId, status, location(commandId),
                PublicationOutcome.DURABLY_QUEUED, PublicationResolution.RETAINED, cause);
    }

    private static AsyncOrderSubmission retryable(
            String commandId, String cause, PublicationResolution resolution) {
        return new AsyncOrderSubmission(commandId, CommandStatus.UNRESOLVED, location(commandId),
                PublicationOutcome.DEFINITELY_NOT_PUBLISHED, resolution, cause);
    }

    private static URI location(String commandId) {
        return URI.create("/api/v2/order-commands/" + commandId);
    }
}
