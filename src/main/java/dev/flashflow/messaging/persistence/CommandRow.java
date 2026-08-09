package dev.flashflow.messaging.persistence;

import java.time.LocalDateTime;

public record CommandRow(
        String commandId, String operationName, String callerId, String idempotencyKey,
        String activitySkuId, String payloadFingerprint, int schemaVersion, String status,
        String resultCode, String orderId, int attemptCount, LocalDateTime createdAt,
        LocalDateTime updatedAt, LocalDateTime completedAt) {
}
