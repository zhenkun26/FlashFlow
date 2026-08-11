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
        ADMISSION_MODE,
        INJECTED_FAILURE,
        STRATEGY,
        VUS,
        POOL_SIZE,
        TRANSACTION_RETRY_BUDGET,
        TRANSACTION_SEQUENCE,
        MESSAGING_MODE,
        STOCK_LEVEL,
        SKU_CONTENTION_SHAPE
    }

    public enum InjectedFailure {
        NONE,
        REDIS_OUTAGE,
        AMBIGUOUS_REPLY,
        RESTART_STATE_LOSS,
        DUPLICATE_LIFECYCLE,
        DRIFT_REBUILD
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
            FlashFlowProperties.TransactionSequence transactionSequence,
            FlashFlowProperties.AdmissionMode admissionMode,
            int heldResolutionSeconds,
            String redisImage,
            String scriptVersion,
            String generation,
            InjectedFailure injectedFailure,
            Messaging messaging) {

        public Case(
                String id, String profile, FlashFlowProperties.Strategy strategy, int vus,
                int durationSeconds, int initialStock, SkuDistribution skuDistribution,
                int skuCount, int poolSize, int connectionTimeoutMs, int optimisticMaxRetries,
                int transactionMaxRetries, FlashFlowProperties.TransactionSequence transactionSequence) {
            this(id, profile, strategy, vus, durationSeconds, initialStock, skuDistribution,
                    skuCount, poolSize, connectionTimeoutMs, optimisticMaxRetries,
                    transactionMaxRetries, transactionSequence,
                    FlashFlowProperties.AdmissionMode.MYSQL_ONLY, 30,
                    "none", "v2-1", "none", InjectedFailure.NONE, null);
        }
    }

    public record Messaging(
            String brokerImage,
            String clientVersion,
            String topology,
            String acknowledgementMode,
            int producerRetries,
            String delayMechanism,
            String injectedFault,
            LiveMessaging live) {
        public Messaging(String brokerImage, String clientVersion, String topology,
                         String acknowledgementMode, int producerRetries,
                         String delayMechanism, String injectedFault) {
            this(brokerImage, clientVersion, topology, acknowledgementMode,
                    producerRetries, delayMechanism, injectedFault, null);
        }
    }

    public record LiveMessaging(
            String mode,
            String brokerVersion,
            String namesrvAddr,
            String orderTopic,
            String orderConsumerGroup,
            String expirationTopic,
            String expirationConsumerGroup,
            String deadLetterTopic,
            int sendTimeoutMs,
            int maxReconsumeTimes,
            int delayLevel,
            int drainSeconds,
            String injectedFault) {
    }

    public record Comparison(String id, Factor factor, List<String> caseIds) {
    }
}
