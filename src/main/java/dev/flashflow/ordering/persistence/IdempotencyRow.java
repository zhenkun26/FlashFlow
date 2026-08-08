package dev.flashflow.ordering.persistence;

public record IdempotencyRow(
        String operationName,
        String callerId,
        String idempotencyKey,
        String requestHash,
        String status,
        String resultCode,
        String resourceId) {
}

