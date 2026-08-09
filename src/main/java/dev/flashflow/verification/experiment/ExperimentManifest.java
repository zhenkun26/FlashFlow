package dev.flashflow.verification.experiment;

import dev.flashflow.shared.config.FlashFlowProperties;
import java.util.List;

public record ExperimentManifest(
        int schemaVersion,
        List<Case> cases,
        List<Comparison> comparisons) {

    public enum SkuDistribution {
        SINGLE_HOT,
        UNIFORM,
        ZIPF_HOT
    }

    public enum Factor {
        STRATEGY,
        VUS,
        POOL_SIZE,
        TRANSACTION_RETRY_BUDGET,
        TRANSACTION_SEQUENCE,
        STOCK_LEVEL,
        SKU_CONTENTION_SHAPE
    }

    public record Case(
            String id,
            String profile,
            FlashFlowProperties.Strategy strategy,
            int vus,
            int durationSeconds,
            int initialStock,
            SkuDistribution skuDistribution,
            int skuCount,
            int poolSize,
            int connectionTimeoutMs,
            int optimisticMaxRetries,
            int transactionMaxRetries,
            FlashFlowProperties.TransactionSequence transactionSequence) {
    }

    public record Comparison(String id, Factor factor, List<String> caseIds) {
    }
}
