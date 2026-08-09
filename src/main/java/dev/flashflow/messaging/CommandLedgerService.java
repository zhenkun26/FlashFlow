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
        if (status != CommandStatus.ACCEPTED && status != CommandStatus.RETRYABLE
                && status != CommandStatus.UNRESOLVED) {
            throw new IllegalArgumentException("Unsupported non-terminal command state: " + status);
        }
        mapper.markNonTerminal(commandId, status.name(), now());
        return require(commandId);
    }

    public CommandRow require(String commandId) {
        CommandRow row = mapper.findById(commandId);
        if (row == null) throw new IllegalArgumentException("Unknown command: " + commandId);
        return row;
    }

    public CommandSummary summary(String commandId) {
        CommandRow row = require(commandId);
        return new CommandSummary(row.commandId(), row.schemaVersion(), CommandStatus.valueOf(row.status()),
                row.resultCode(), row.orderId(), row.attemptCount(), row.updatedAt());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
