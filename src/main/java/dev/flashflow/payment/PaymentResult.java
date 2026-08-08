package dev.flashflow.payment;

public record PaymentResult(Code code, String paymentId, PaymentApplyStatus applyStatus, String message) {
    public enum Code {
        APPLIED,
        REFUND_REQUIRED,
        DUPLICATE,
        CALLBACK_CONFLICT,
        PROVIDER_TRANSACTION_CONFLICT,
        ORDER_ALREADY_PAID,
        INVALID_AMOUNT,
        ORDER_NOT_FOUND
    }
}

