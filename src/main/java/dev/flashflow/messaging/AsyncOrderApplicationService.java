package dev.flashflow.messaging;

import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionDecision;
import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.admission.AdmissionPort;
import dev.flashflow.admission.AdmissionResult;
import dev.flashflow.messaging.persistence.CommandRow;
import dev.flashflow.ordering.PlaceOrderCommand;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import java.net.URI;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "flashflow.messaging", name = "mode", havingValue = "LIVE")
public final class AsyncOrderApplicationService {
    private static final Logger log = LoggerFactory.getLogger(AsyncOrderApplicationService.class);
    private final OrderCommandFactory commands;
    private final CommandLedgerService ledger;
    private final AdmissionPort admission;
    private final AdmissionIdentity identities;
    private final FlashFlowProperties properties;
    private final DeterministicPublicationCoordinator publicationCoordinator;
    private final OrderCommandPublisher publisher;
    private final FlashFlowMetrics metrics;
    private final Clock clock;

    public AsyncOrderApplicationService(
            OrderCommandFactory commands,
            CommandLedgerService ledger,
            AdmissionPort admission,
            AdmissionIdentity identities,
            FlashFlowProperties properties,
            DeterministicPublicationCoordinator publicationCoordinator,
            OrderCommandPublisher publisher,
            FlashFlowMetrics metrics,
            Clock clock) {
        this.commands = commands;
        this.ledger = ledger;
        this.admission = admission;
        this.identities = identities;
        this.properties = properties;
        this.publicationCoordinator = publicationCoordinator;
        this.publisher = publisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    public AsyncOrderSubmission submit(PlaceOrderCommand command, String traceId) {
        OrderCommandEnvelope envelope = commands.create(command, traceId);
        CommandRow row = ledger.prepare(envelope);
        CommandStatus existing = CommandStatus.valueOf(row.status());
        if (existing == CommandStatus.ACCEPTED || existing == CommandStatus.PROCESSING
                || existing == CommandStatus.COMPLETED || existing == CommandStatus.REJECTED) {
            return new AsyncOrderSubmission(envelope.commandId(), existing, location(envelope.commandId()),
                    PublicationOutcome.BROKER_ACKNOWLEDGED, PublicationResolution.RETAINED, "DURABLE_REPLAY");
        }

        AdmissionCommand admissionCommand = new AdmissionCommand(command.activitySkuId(), envelope.commandId(),
                identities.userDigest(command.activitySkuId(), command.userId()),
                clock.instant().plus(properties.admission().heldResolution()));
        AdmissionResult admitted = admission.acquire(admissionCommand);
        metrics.admissionDecision(admitted.decision().name());
        if (!admitted.permitsMySqlAttempt()) {
            ledger.markPublication(envelope.commandId(), CommandStatus.RETRYABLE,
                    "ADMISSION_" + admitted.decision().name());
            return new AsyncOrderSubmission(envelope.commandId(), CommandStatus.RETRYABLE,
                    location(envelope.commandId()), PublicationOutcome.DEFINITELY_NOT_PUBLISHED,
                    PublicationResolution.RELEASED, "ADMISSION_" + admitted.decision().name());
        }

        PublicationResult publication = publisher.publish(envelope);
        PublicationResolution resolution = publicationCoordinator.resolve(
                admissionCommand, admitted.generation(), publication);
        CommandStatus status = resolution == PublicationResolution.UNRESOLVED
                ? CommandStatus.UNRESOLVED
                : switch (publication.outcome()) {
                    case BROKER_ACKNOWLEDGED -> CommandStatus.ACCEPTED;
                    case DEFINITELY_NOT_PUBLISHED -> CommandStatus.RETRYABLE;
                    case AMBIGUOUS -> CommandStatus.UNRESOLVED;
                };
        String cause = resolution == PublicationResolution.UNRESOLVED
                ? publication.cause() + "_ADMISSION_UNRESOLVED" : publication.cause();
        ledger.markPublication(envelope.commandId(), status, cause);
        log.info("Asynchronous publication command_id={} outcome={} status={} cause={}",
                envelope.commandId(), publication.outcome(), status, cause);
        return new AsyncOrderSubmission(envelope.commandId(), status, location(envelope.commandId()),
                publication.outcome(), resolution, cause);
    }

    private static URI location(String commandId) {
        return URI.create("/api/v2/order-commands/" + commandId);
    }
}
