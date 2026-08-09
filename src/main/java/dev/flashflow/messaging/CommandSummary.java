package dev.flashflow.messaging;

import java.time.LocalDateTime;

public record CommandSummary(
        String commandId,
        int schemaVersion,
        CommandStatus status,
        String resultCode,
        String orderId,
        int attemptCount,
        LocalDateTime updatedAt) {
}
