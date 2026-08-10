package dev.flashflow.verification.experiment;

import dev.flashflow.shared.config.FlashFlowProperties;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExperimentManifestValidator {
    private ExperimentManifestValidator() {
    }

    public static void requireValid(ExperimentManifest manifest) {
        List<String> errors = validate(manifest);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid experiment manifest:\n- " + String.join("\n- ", errors));
        }
    }

    public static List<String> validate(ExperimentManifest manifest) {
        List<String> errors = new ArrayList<>();
        if (manifest == null) {
            return List.of("manifest is required");
        }
        if (manifest.schemaVersion() != 1) {
            errors.add("schemaVersion must be 1");
        }
        if (manifest.cases() == null || manifest.cases().isEmpty()) {
            errors.add("at least one case is required");
            return List.copyOf(errors);
        }

        Map<String, ExperimentManifest.Case> cases = new HashMap<>();
        for (ExperimentManifest.Case experimentCase : manifest.cases()) {
            validateCase(experimentCase, errors);
            if (experimentCase != null && experimentCase.id() != null
                    && cases.put(experimentCase.id(), experimentCase) != null) {
                errors.add("duplicate case id: " + experimentCase.id());
            }
        }

        Set<String> comparisonIds = new HashSet<>();
        if (manifest.comparisons() != null) {
            for (ExperimentManifest.Comparison comparison : manifest.comparisons()) {
                validateComparison(comparison, cases, comparisonIds, errors);
            }
        }
        return List.copyOf(errors);
    }

    private static void validateCase(ExperimentManifest.Case value, List<String> errors) {
        if (value == null) {
            errors.add("case is required");
            return;
        }
        String prefix = "case " + value.id() + ": ";
        if (value.id() == null || !value.id().matches("[a-z0-9][a-z0-9-]*")) {
            errors.add(prefix + "id must be kebab-case");
        }
        if (!Set.of("local", "lab", "messaging-spike", "messaging-live").contains(value.profile())) {
            errors.add(prefix + "profile must be local, lab, messaging-spike, or messaging-live");
        }
        if (value.strategy() == null) {
            errors.add(prefix + "strategy is required");
        }
        if (value.strategy() == FlashFlowProperties.Strategy.UNSAFE_READ_THEN_WRITE
                && !"lab".equals(value.profile())) {
            errors.add(prefix + "unsafe strategy requires the lab profile");
        }
        positive(prefix, "vus", value.vus(), errors);
        positive(prefix, "durationSeconds", value.durationSeconds(), errors);
        nonNegative(prefix, "initialStock", value.initialStock(), errors);
        positive(prefix, "skuCount", value.skuCount(), errors);
        positive(prefix, "poolSize", value.poolSize(), errors);
        positive(prefix, "connectionTimeoutMs", value.connectionTimeoutMs(), errors);
        range(prefix, "optimisticMaxRetries", value.optimisticMaxRetries(), 0, 20, errors);
        range(prefix, "transactionMaxRetries", value.transactionMaxRetries(), 0, 20, errors);
        if (value.transactionSequence() == null) {
            errors.add(prefix + "transactionSequence is required");
        } else if (value.transactionSequence() == FlashFlowProperties.TransactionSequence.CHILD_FIRST_LEGACY
                && !"lab".equals(value.profile())) {
            errors.add(prefix + "legacy child-first sequence requires the lab profile");
        }
        if (value.admissionMode() == null) {
            errors.add(prefix + "admissionMode is required");
        }
        positive(prefix, "heldResolutionSeconds", value.heldResolutionSeconds(), errors);
        if (value.scriptVersion() == null || value.scriptVersion().isBlank()) {
            errors.add(prefix + "scriptVersion is required");
        }
        if (value.injectedFailure() == null) {
            errors.add(prefix + "injectedFailure is required");
        } else if (value.admissionMode() == FlashFlowProperties.AdmissionMode.MYSQL_ONLY
                && value.injectedFailure() != ExperimentManifest.InjectedFailure.NONE) {
            errors.add(prefix + "Redis failure injection requires REDIS_LUA admission");
        }
        if (value.admissionMode() == FlashFlowProperties.AdmissionMode.REDIS_LUA) {
            if (value.redisImage() == null || !value.redisImage().matches("redis:[a-zA-Z0-9._-]+")) {
                errors.add(prefix + "REDIS_LUA requires a pinned redisImage");
            }
            if (value.generation() == null || value.generation().isBlank()
                    || "none".equalsIgnoreCase(value.generation())) {
                errors.add(prefix + "REDIS_LUA requires a generation");
            }
        }
        if (value.skuDistribution() == null) {
            errors.add(prefix + "skuDistribution is required");
        } else if (value.skuDistribution() == ExperimentManifest.SkuDistribution.SINGLE_HOT
                && value.skuCount() != 1) {
            errors.add(prefix + "SINGLE_HOT requires skuCount=1");
        } else if (value.skuDistribution() != ExperimentManifest.SkuDistribution.SINGLE_HOT
                && value.skuCount() < 2) {
            errors.add(prefix + value.skuDistribution() + " requires skuCount>=2");
        }
        if ("messaging-spike".equals(value.profile()) || "messaging-live".equals(value.profile())) {
            ExperimentManifest.Messaging messaging = value.messaging();
            if (messaging == null) {
                errors.add(prefix + value.profile() + " profile requires messaging inputs");
            } else {
                if (!"apache/rocketmq:5.3.4".equals(messaging.brokerImage())) {
                    errors.add(prefix + "brokerImage must pin apache/rocketmq:5.3.4");
                }
                String expectedClient = "messaging-live".equals(value.profile()) ? "5.3.3" : "5.3.4-mqadmin";
                if (!expectedClient.equals(messaging.clientVersion())) {
                    errors.add(prefix + "clientVersion must pin " + expectedClient);
                }
                if (messaging.producerRetries() < 0 || messaging.producerRetries() > 3) {
                    errors.add(prefix + "producerRetries must be between 0 and 3");
                }
                if (messaging.topology() == null || messaging.topology().isBlank()
                        || messaging.acknowledgementMode() == null || messaging.acknowledgementMode().isBlank()
                        || messaging.delayMechanism() == null || messaging.delayMechanism().isBlank()
                        || messaging.injectedFault() == null || messaging.injectedFault().isBlank()) {
                    errors.add(prefix + "messaging inputs must be complete");
                }
                if ("messaging-live".equals(value.profile())) validateLive(prefix, messaging.live(), errors);
            }
        } else if (value.messaging() != null) {
            errors.add(prefix + "messaging inputs require a messaging profile");
        }
    }

    private static void validateLive(
            String prefix, ExperimentManifest.LiveMessaging live, List<String> errors) {
        if (live == null) {
            errors.add(prefix + "messaging-live requires live inputs");
            return;
        }
        if (!"LIVE".equals(live.mode()) || !"5.3.4".equals(live.brokerVersion())) {
            errors.add(prefix + "live mode and brokerVersion must be pinned");
        }
        if (live.namesrvAddr() == null || live.namesrvAddr().isBlank()
                || live.orderTopic() == null || live.orderTopic().isBlank()
                || live.orderConsumerGroup() == null || live.orderConsumerGroup().isBlank()
                || live.expirationTopic() == null || live.expirationTopic().isBlank()
                || live.expirationConsumerGroup() == null || live.expirationConsumerGroup().isBlank()
                || live.deadLetterTopic() == null || live.deadLetterTopic().isBlank()
                || live.injectedFault() == null || live.injectedFault().isBlank()) {
            errors.add(prefix + "live topic, group, endpoint, and fault inputs must be complete");
        }
        range(prefix, "sendTimeoutMs", live.sendTimeoutMs(), 1, 60000, errors);
        range(prefix, "maxReconsumeTimes", live.maxReconsumeTimes(), 0, 100, errors);
        range(prefix, "delayLevel", live.delayLevel(), 1, 18, errors);
        range(prefix, "drainSeconds", live.drainSeconds(), 1, 3600, errors);
    }

    private static void validateComparison(
            ExperimentManifest.Comparison comparison,
            Map<String, ExperimentManifest.Case> cases,
            Set<String> comparisonIds,
            List<String> errors) {
        if (comparison == null) {
            errors.add("comparison is required");
            return;
        }
        String prefix = "comparison " + comparison.id() + ": ";
        if (comparison.id() == null || !comparison.id().matches("[a-z0-9][a-z0-9-]*")) {
            errors.add(prefix + "id must be kebab-case");
        } else if (!comparisonIds.add(comparison.id())) {
            errors.add(prefix + "id is duplicated");
        }
        if (comparison.factor() == null) {
            errors.add(prefix + "factor is required");
            return;
        }
        if (comparison.caseIds() == null || comparison.caseIds().size() != 2) {
            errors.add(prefix + "exactly two caseIds are required");
            return;
        }
        ExperimentManifest.Case left = cases.get(comparison.caseIds().get(0));
        ExperimentManifest.Case right = cases.get(comparison.caseIds().get(1));
        if (left == null || right == null) {
            errors.add(prefix + "references an unknown case");
            return;
        }
        Set<ExperimentManifest.Factor> changed = changedFactors(left, right);
        if (!changed.equals(EnumSet.of(comparison.factor()))) {
            errors.add(prefix + "must change only " + comparison.factor() + " but changed " + changed);
        }
    }

    static Set<ExperimentManifest.Factor> changedFactors(
            ExperimentManifest.Case left, ExperimentManifest.Case right) {
        EnumSet<ExperimentManifest.Factor> changed = EnumSet.noneOf(ExperimentManifest.Factor.class);
        if (left.admissionMode() != right.admissionMode()
                || left.heldResolutionSeconds() != right.heldResolutionSeconds()
                || !java.util.Objects.equals(left.redisImage(), right.redisImage())
                || !java.util.Objects.equals(left.scriptVersion(), right.scriptVersion())
                || !java.util.Objects.equals(left.generation(), right.generation())) {
            changed.add(ExperimentManifest.Factor.ADMISSION_MODE);
        }
        if (left.injectedFailure() != right.injectedFailure()) {
            changed.add(ExperimentManifest.Factor.INJECTED_FAILURE);
        }
        if (left.strategy() != right.strategy()) changed.add(ExperimentManifest.Factor.STRATEGY);
        if (left.vus() != right.vus()) changed.add(ExperimentManifest.Factor.VUS);
        if (left.poolSize() != right.poolSize()
                || left.connectionTimeoutMs() != right.connectionTimeoutMs()) {
            changed.add(ExperimentManifest.Factor.POOL_SIZE);
        }
        if (left.transactionMaxRetries() != right.transactionMaxRetries()
                || left.optimisticMaxRetries() != right.optimisticMaxRetries()) {
            changed.add(ExperimentManifest.Factor.TRANSACTION_RETRY_BUDGET);
        }
        if (left.transactionSequence() != right.transactionSequence()) {
            changed.add(ExperimentManifest.Factor.TRANSACTION_SEQUENCE);
        }
        if (left.initialStock() != right.initialStock()) changed.add(ExperimentManifest.Factor.STOCK_LEVEL);
        if (left.skuDistribution() != right.skuDistribution() || left.skuCount() != right.skuCount()) {
            changed.add(ExperimentManifest.Factor.SKU_CONTENTION_SHAPE);
        }
        if (!left.profile().equals(right.profile()) || left.durationSeconds() != right.durationSeconds()) {
            return EnumSet.allOf(ExperimentManifest.Factor.class);
        }
        return changed;
    }

    private static void positive(String prefix, String field, int value, List<String> errors) {
        if (value < 1) errors.add(prefix + field + " must be positive");
    }

    private static void nonNegative(String prefix, String field, int value, List<String> errors) {
        if (value < 0) errors.add(prefix + field + " must be non-negative");
    }

    private static void range(String prefix, String field, int value, int min, int max, List<String> errors) {
        if (value < min || value > max) errors.add(prefix + field + " must be between " + min + " and " + max);
    }
}
