package dev.flashflow.messaging.outbox;

import dev.flashflow.messaging.OrderCommandEnvelope;
import dev.flashflow.messaging.OrderCommandPublisher;
import dev.flashflow.messaging.MessagingFaultInjector;
import dev.flashflow.messaging.PublicationOutcome;
import dev.flashflow.messaging.PublicationResult;
import dev.flashflow.messaging.persistence.OutboxRow;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.MessagingProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "flashflow.messaging", name = "mode", havingValue = "OUTBOX")
public final class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final String ORDER_TAG = "ORDER_V1";
    private final OutboxStore store;
    private final OrderCommandPublisher publisher;
    private final MessagingProperties properties;
    private final FlashFlowMetrics metrics;
    private final MessagingFaultInjector faults;
    private final Clock clock;
    private final AtomicBoolean acceptingClaims = new AtomicBoolean(true);

    public OutboxDispatcher(OutboxStore store, OrderCommandPublisher publisher,
                            MessagingProperties properties, FlashFlowMetrics metrics,
                            MessagingFaultInjector faults, Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.metrics = metrics;
        this.faults = faults;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${flashflow.messaging.outbox.poll-interval:250ms}")
    public void scheduledDispatch() {
        if (!properties.outbox().dispatchEnabled() || !acceptingClaims.get()) return;
        try {
            dispatchBatch();
            OutboxBacklog backlog = store.backlog();
            metrics.outboxSnapshot(backlog.ready(), backlog.oldestAgeMillis(), backlog.ready());
        } catch (RuntimeException failure) {
            metrics.outbox("POLL", "FAILED");
            log.warn("Outbox dispatch poll failed cause={}", bounded(failure.getClass().getSimpleName()));
        }
    }

    public int dispatchBatch() {
        if (!acceptingClaims.get()) return 0;
        List<ClaimedOutbox> claims = store.claimBatch(properties.outbox().batchSize(),
                properties.outbox().leaseOwner(), properties.outbox().leaseDuration());
        for (ClaimedOutbox claim : claims) dispatch(claim);
        return claims.size();
    }

    private void dispatch(ClaimedOutbox claim) {
        OutboxRow row = claim.row();
        metrics.outbox("CLAIM", claim.leaseTakeover() ? "TAKEOVER" : "ACQUIRED");
        OrderCommandEnvelope envelope;
        try {
            requireRouting(row);
            envelope = store.envelope(row);
        } catch (IllegalArgumentException invalid) {
            store.stop(row, OutboxStatus.INVALID, invalid.getMessage());
            metrics.outbox("DISPOSITION", "INVALID");
            return;
        }

        PublicationResult result;
        try {
            result = publisher.publish(envelope);
        } catch (RuntimeException failure) {
            result = new PublicationResult(PublicationOutcome.AMBIGUOUS,
                    bounded(failure.getClass().getSimpleName()));
        }
        if (result.outcome() == PublicationOutcome.BROKER_ACKNOWLEDGED) {
            faults.afterBrokerAcknowledgementBeforeOutboxRecord(row.commandId());
            boolean recorded = store.acknowledge(row, result.cause());
            metrics.outbox("PUBLICATION", recorded ? "ACKNOWLEDGED" : "STALE_OWNER");
            log.info("Outbox publication outbox_id={} command_id={} outcome={}",
                    row.outboxId(), row.commandId(), recorded ? "ACKNOWLEDGED" : "STALE_OWNER");
            return;
        }
        if (result.outcome() == PublicationOutcome.DURABLY_QUEUED) {
            store.stop(row, OutboxStatus.INVALID, "NESTED_DURABLY_QUEUED");
            metrics.outbox("DISPOSITION", "INVALID");
            return;
        }
        if (row.attemptCount() >= properties.outbox().maxAttempts()) {
            store.stop(row, OutboxStatus.EXHAUSTED, result.cause());
            metrics.outbox("DISPOSITION", "EXHAUSTED");
            log.warn("Outbox publication exhausted outbox_id={} command_id={} cause={}",
                    row.outboxId(), row.commandId(), bounded(result.cause()));
            return;
        }
        boolean recorded = store.retry(row, result.cause(), now().plus(backoff(row.attemptCount())));
        metrics.outbox("PUBLICATION", recorded ? "RETRYABLE" : "STALE_OWNER");
        log.info("Outbox publication deferred outbox_id={} command_id={} outcome={} cause={}",
                row.outboxId(), row.commandId(), recorded ? "RETRYABLE" : "STALE_OWNER",
                bounded(result.cause()));
    }

    private void requireRouting(OutboxRow row) {
        if (!properties.orderTopic().equals(row.topicName()) || !ORDER_TAG.equals(row.tagName())) {
            throw new IllegalArgumentException("Outbox routing metadata mismatch");
        }
    }

    private Duration backoff(int attempt) {
        int shift = Math.min(Math.max(0, attempt - 1), 30);
        Duration calculated;
        try {
            calculated = properties.outbox().initialBackoff().multipliedBy(1L << shift);
        } catch (ArithmeticException overflow) {
            return properties.outbox().maxBackoff();
        }
        return calculated.compareTo(properties.outbox().maxBackoff()) > 0
                ? properties.outbox().maxBackoff() : calculated;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @PreDestroy
    void stopClaiming() {
        acceptingClaims.set(false);
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        String normalized = value.toUpperCase().replaceAll("[^A-Z0-9_]+", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }
}
