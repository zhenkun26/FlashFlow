package dev.flashflow.payment.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentCallbackRequest(
        @NotBlank @Size(max = 128) String providerEventId,
        @NotBlank @Size(max = 128) String providerTransactionId,
        @NotBlank @Size(max = 36) String orderId,
        @NotNull @DecimalMin("0.00") BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull LocalDateTime paidAt) {
}

