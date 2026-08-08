package dev.flashflow.payment.persistence;

import java.math.BigDecimal;

public record PaymentRow(
        String id,
        String orderId,
        String providerTransactionId,
        String status,
        String applyStatus,
        BigDecimal amount,
        String currency) {
}

