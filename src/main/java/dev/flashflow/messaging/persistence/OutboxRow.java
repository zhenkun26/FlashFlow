package dev.flashflow.messaging.persistence;

import java.time.LocalDateTime;

public record OutboxRow(
        String outboxId,
        String commandId,
        int schemaVersion,
        String envelopePayload,
        String envelopeFingerprint,
        String topicName,
        String tagName,
        String status,
        int attemptCount,
        LocalDateTime nextAttemptAt,
        String leaseToken,
        String leaseOwner,
        LocalDateTime leaseUntil,
        String resultCode,
        LocalDateTime acknowledgedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
