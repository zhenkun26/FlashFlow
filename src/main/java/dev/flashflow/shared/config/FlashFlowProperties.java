package dev.flashflow.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "flashflow")
public record FlashFlowProperties(
        @Valid @NotNull Inventory inventory,
        @Valid @NotNull Ordering ordering,
        @Valid @NotNull Expiration expiration) {

    public record Inventory(
            @NotNull Strategy strategy,
            @Min(0) @Max(20) int optimisticMaxRetries) {
    }

    public record Ordering(
            @Min(0) @Max(20) int transactionMaxRetries,
            @Min(0) @Max(5) int claimRaceMaxRetries,
            @NotNull TransactionSequence transactionSequence) {
    }

    public record Expiration(
            @NotNull Duration orderTtl,
            @Min(1) @Max(1000) int batchSize,
            boolean schedulingEnabled) {
    }

    public enum Strategy {
        CONDITIONAL_ATOMIC,
        PESSIMISTIC,
        OPTIMISTIC,
        UNSAFE_READ_THEN_WRITE
    }

    public enum TransactionSequence {
        STOCK_FIRST,
        CHILD_FIRST_LEGACY
    }
}
