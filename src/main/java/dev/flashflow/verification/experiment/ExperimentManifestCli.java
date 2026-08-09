package dev.flashflow.verification.experiment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;

public final class ExperimentManifestCli {
    private ExperimentManifestCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: validate <manifest> | resolve <manifest> <case-id> | list <manifest>");
        }
        ExperimentManifest manifest = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(Path.of(args[1]).toFile(), ExperimentManifest.class);
        ExperimentManifestValidator.requireValid(manifest);
        switch (args[0]) {
            case "validate" -> System.out.println("VALID " + manifest.cases().size() + " cases");
            case "list" -> manifest.cases().forEach(value -> System.out.println(value.id()));
            case "resolve" -> {
                if (args.length != 3) throw new IllegalArgumentException("resolve requires a case id");
                ExperimentManifest.Case value = manifest.cases().stream()
                        .filter(candidate -> candidate.id().equals(args[2]))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown case: " + args[2]));
                printResolved(value);
            }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    private static void printResolved(ExperimentManifest.Case value) {
        System.out.println("CASE_ID=" + value.id());
        System.out.println("PROFILE=" + value.profile());
        System.out.println("STRATEGY=" + value.strategy());
        System.out.println("VUS=" + value.vus());
        System.out.println("DURATION_SECONDS=" + value.durationSeconds());
        System.out.println("INITIAL_STOCK=" + value.initialStock());
        System.out.println("SKU_DISTRIBUTION=" + value.skuDistribution());
        System.out.println("SKU_COUNT=" + value.skuCount());
        System.out.println("POOL_SIZE=" + value.poolSize());
        System.out.println("CONNECTION_TIMEOUT_MS=" + value.connectionTimeoutMs());
        System.out.println("OPTIMISTIC_MAX_RETRIES=" + value.optimisticMaxRetries());
        System.out.println("TRANSACTION_MAX_RETRIES=" + value.transactionMaxRetries());
        System.out.println("TRANSACTION_SEQUENCE=" + value.transactionSequence());
        System.out.println("ADMISSION_MODE=" + value.admissionMode());
        System.out.println("HELD_RESOLUTION_SECONDS=" + value.heldResolutionSeconds());
        System.out.println("REDIS_IMAGE=" + value.redisImage());
        System.out.println("SCRIPT_VERSION=" + value.scriptVersion());
        System.out.println("GENERATION=" + value.generation());
        System.out.println("INJECTED_FAILURE=" + value.injectedFailure());
        if (value.messaging() != null) {
            System.out.println("BROKER_IMAGE=" + value.messaging().brokerImage());
            System.out.println("CLIENT_VERSION=" + value.messaging().clientVersion());
            System.out.println("MESSAGING_TOPOLOGY=" + value.messaging().topology());
            System.out.println("ACKNOWLEDGEMENT_MODE=" + value.messaging().acknowledgementMode());
            System.out.println("PRODUCER_RETRIES=" + value.messaging().producerRetries());
            System.out.println("DELAY_MECHANISM=" + value.messaging().delayMechanism());
            System.out.println("MESSAGING_INJECTED_FAULT=" + value.messaging().injectedFault());
        }
    }
}
