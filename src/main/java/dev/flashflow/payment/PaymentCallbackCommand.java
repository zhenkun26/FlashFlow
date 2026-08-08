package dev.flashflow.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

public record PaymentCallbackCommand(
        String providerEventId,
        String providerTransactionId,
        String orderId,
        BigDecimal amount,
        String currency,
        LocalDateTime paidAt) {

    public PaymentCallbackCommand {
        providerEventId = require(providerEventId, "providerEventId", 128);
        providerTransactionId = require(providerTransactionId, "providerTransactionId", 128);
        orderId = require(orderId, "orderId", 36);
        Objects.requireNonNull(amount, "amount is required");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        currency = require(currency, "currency", 3).toUpperCase();
        Objects.requireNonNull(paidAt, "paidAt is required");
    }

    private static String require(String value, String name, int max) {
        Objects.requireNonNull(value, name + " is required");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalArgumentException(name + " must contain 1-" + max + " characters");
        }
        return normalized;
    }
}

