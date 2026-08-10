package dev.flashflow.messaging;

import dev.flashflow.messaging.persistence.CommandMapper;
import dev.flashflow.messaging.persistence.CommandRow;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;

@Service
public final class CommandLedgerService {
    private final CommandMapper mapper;
    private final Clock clock;

    public CommandLedgerService(CommandMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public CommandRow prepare(OrderCommandEnvelope envelope) {
        LocalDateTime now = now();
        mapper.tryInsert(envelope.commandId(), envelope.callerId(), envelope.idempotencyKey(),
                envelope.activitySkuId(), envelope.payloadFingerprint(), envelope.schemaVersion(), now);
        CommandRow row = require(envelope.commandId());
        if (!row.callerId().equals(envelope.callerId())
                || !row.idempotencyKey().equals(envelope.idempotencyKey())
                || !row.activitySkuId().equals(envelope.activitySkuId())
                || !row.payloadFingerprint().equals(envelope.payloadFingerprint())
                || row.schemaVersion() != envelope.schemaVersion()) {
            throw new CommandConflictException("Command identity was reused with a different payload");
        }
        return row;
    }

    public boolean claim(String commandId) {
        return mapper.claim(commandId, now()) == 1;
    }

    public CommandRow finish(String commandId, CommandStatus status, String resultCode, String orderId) {
        if (status != CommandStatus.COMPLETED && status != CommandStatus.REJECTED) {
            throw new IllegalArgumentException("Only terminal command states can be finished");
        }
        mapper.finish(commandId, status.name(), resultCode, orderId, now());
        return require(commandId);
    }

    public CommandRow mark(String commandId, CommandStatus status) {
        return mark(commandId, status, status.name(), false);
    }

    public CommandRow markPublication(String commandId, CommandStatus status, String cause) {
        return mark(commandId, status, cause, true);
    }

    private CommandRow mark(String commandId, CommandStatus status, String cause, boolean publicationAttempt) {
        if (status != CommandStatus.ACCEPTED && status != CommandStatus.RETRYABLE
                && status != CommandStatus.UNRESOLVED) {
            throw new IllegalArgumentException("Unsupported non-terminal command state: " + status);
        }
        mapper.markNonTerminal(commandId, status.name(), bounded(cause), publicationAttempt ? 1 : 0, now());
        return require(commandId);
    }

    public CommandRow markDeadLetter(String commandId, String cause) {
        mapper.markDeadLetter(commandId, bounded(cause), now());
        return require(commandId);
    }

    public CommandRow require(String commandId) {
        CommandRow row = mapper.findById(commandId);
        if (row == null) throw new IllegalArgumentException("Unknown command: " + commandId);
        return row;
    }

    public CommandSummary summary(String commandId) {
        CommandRow row = require(commandId);
        return summary(row);
    }

    public CommandSummary summaryForCaller(String commandId, String callerId) {
        CommandRow row = mapper.findByIdAndCaller(commandId, callerId);
        return row == null ? null : summary(row);
    }

    private static CommandSummary summary(CommandRow row) {
        return new CommandSummary(row.commandId(), row.schemaVersion(), CommandStatus.valueOf(row.status()),
                row.resultCode(), row.orderId(), row.attemptCount(), row.updatedAt());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String bounded(String cause) {
        if (cause == null || cause.isBlank()) return "UNKNOWN";
        String normalized = cause.toUpperCase().replaceAll("[^A-Z0-9_]+", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }
}
