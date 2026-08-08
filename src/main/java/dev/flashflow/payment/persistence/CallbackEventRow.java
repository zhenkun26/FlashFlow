package dev.flashflow.payment.persistence;

public record CallbackEventRow(
        String providerEventId,
        String providerTransactionId,
        String orderId,
        String requestHash,
        String resultCode,
        String paymentId) {
}

