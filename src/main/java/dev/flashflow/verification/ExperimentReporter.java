package dev.flashflow.verification;

import dev.flashflow.verification.persistence.InvariantSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ExperimentReporter {
    private ExperimentReporter() {
    }

    public static ExperimentReport report(
            String strategy,
            int initialStock,
            int concurrency,
            int created,
            long conflicts,
            List<Long> latencyMicros,
            Map<String, String> environment,
            InvariantSnapshot invariants,
            String verificationStatus) {
        List<Long> sorted = new ArrayList<>(latencyMicros);
        sorted.sort(Long::compareTo);
        return new ExperimentReport(
                Instant.now(), strategy, initialStock, concurrency, created, concurrency - created,
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                conflicts, Map.copyOf(environment), invariants, verificationStatus);
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}

