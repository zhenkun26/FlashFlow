package dev.flashflow.admission.persistence;

public record EffectiveAdmissionRow(
        String orderId, String userId, String status, String callerId, String idempotencyKey) {
}
