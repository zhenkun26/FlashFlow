package dev.flashflow.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flashflow.messaging.CommandConflictException;
import dev.flashflow.messaging.OrderCommandEnvelope;
import dev.flashflow.messaging.persistence.OutboxMapper;
import dev.flashflow.messaging.persistence.OutboxRow;
import dev.flashflow.shared.RequestHash;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxStore {
    private final OutboxMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxStore(OutboxMapper mapper, ObjectMapper objectMapper, Clock clock) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public OutboxRow prepare(OrderCommandEnvelope envelope, String topic, String tag) {
        String payload = serialize(envelope);
        String fingerprint = RequestHash.sha256(payload);
        LocalDateTime now = now();
        mapper.tryInsert(UUID.randomUUID().toString(), envelope.commandId(), envelope.schemaVersion(),
                payload, fingerprint, bounded(topic, 128, "topic"), bounded(tag, 128, "tag"), now);
        OutboxRow row = requireByCommand(envelope.commandId());
        verify(row, envelope, topic, tag);
        return row;
    }

    public void verify(OutboxRow row, OrderCommandEnvelope envelope, String topic, String tag) {
        String payload = serialize(envelope);
        String fingerprint = RequestHash.sha256(payload);
        if (row.schemaVersion() != envelope.schemaVersion()
                || !row.envelopePayload().equals(payload)
                || !row.envelopeFingerprint().equals(fingerprint)
                || !row.topicName().equals(topic)
                || !row.tagName().equals(tag)) {
            throw new CommandConflictException("Command identity was reused with different Outbox content");
        }
    }

    public OutboxRow requireByCommand(String commandId) {
        OutboxRow row = mapper.findByCommandId(commandId);
        if (row == null) throw new IllegalArgumentException("Unknown Outbox command: " + commandId);
        return row;
    }

    public OrderCommandEnvelope envelope(OutboxRow row) {
        String actual = RequestHash.sha256(row.envelopePayload());
        if (!actual.equals(row.envelopeFingerprint())) {
            throw new IllegalArgumentException("Outbox envelope fingerprint mismatch");
        }
        try {
            OrderCommandEnvelope envelope = objectMapper.readValue(row.envelopePayload(), OrderCommandEnvelope.class);
            if (!envelope.commandId().equals(row.commandId()) || envelope.schemaVersion() != row.schemaVersion()) {
                throw new IllegalArgumentException("Outbox envelope identity mismatch");
            }
            return envelope;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox envelope is invalid", exception);
        }
    }

    @Transactional
    public List<ClaimedOutbox> claimBatch(int limit, String owner, Duration leaseDuration) {
        if (limit < 1) throw new IllegalArgumentException("Outbox claim limit must be positive");
        String boundedOwner = bounded(owner, 64, "lease owner");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Outbox lease duration must be positive");
        }
        LocalDateTime now = now();
        LocalDateTime leaseUntil = now.plus(leaseDuration);
        List<ClaimedOutbox> claimed = new ArrayList<>();
        for (OutboxRow candidate : mapper.findEligibleForUpdate(now, limit)) {
            boolean takeover = OutboxStatus.CLAIMED.name().equals(candidate.status());
            String token = UUID.randomUUID().toString();
            if (mapper.claim(candidate.outboxId(), token, boundedOwner, leaseUntil, now) == 1) {
                claimed.add(new ClaimedOutbox(mapper.findById(candidate.outboxId()), takeover));
            }
        }
        return List.copyOf(claimed);
    }

    public boolean acknowledge(OutboxRow claimed, String cause) {
        return mapper.acknowledge(claimed.outboxId(), claimed.leaseToken(), boundedCause(cause), now()) == 1;
    }

    public boolean retry(OutboxRow claimed, String cause, LocalDateTime nextAttemptAt) {
        if (nextAttemptAt == null) throw new IllegalArgumentException("nextAttemptAt is required");
        return mapper.retry(claimed.outboxId(), claimed.leaseToken(), boundedCause(cause), nextAttemptAt, now()) == 1;
    }

    public boolean stop(OutboxRow claimed, OutboxStatus status, String cause) {
        if (status != OutboxStatus.INVALID && status != OutboxStatus.EXHAUSTED) {
            throw new IllegalArgumentException("Only INVALID or EXHAUSTED can stop dispatch");
        }
        return mapper.stop(claimed.outboxId(), claimed.leaseToken(), status.name(), boundedCause(cause), now()) == 1;
    }

    public OutboxBacklog backlog() {
        LocalDateTime now = now();
        long ready = mapper.readyCount(now);
        LocalDateTime oldest = mapper.oldestReadyAt(now);
        long age = oldest == null ? 0 : Math.max(0,
                java.time.Duration.between(oldest, now).toMillis());
        return new OutboxBacklog(ready, age);
    }

    public List<OutboxRow> cleanupCandidates(Duration retention, int limit) {
        if (retention == null || retention.isZero() || retention.isNegative() || limit < 1) {
            throw new IllegalArgumentException("Positive Outbox retention and limit are required");
        }
        return List.copyOf(mapper.findCleanupEligible(now().minus(retention), limit));
    }

    private String serialize(OrderCommandEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Order command envelope cannot be serialized", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String boundedCause(String cause) {
        if (cause == null || cause.isBlank()) return "UNKNOWN";
        String normalized = cause.toUpperCase().replaceAll("[^A-Z0-9_]+", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    private static String bounded(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is required and must be at most " + max + " characters");
        }
        return value;
    }
}
