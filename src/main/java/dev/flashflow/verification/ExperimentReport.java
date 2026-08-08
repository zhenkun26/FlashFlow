package dev.flashflow.verification;

import dev.flashflow.verification.persistence.InvariantSnapshot;
import java.time.Instant;
import java.util.Map;

public record ExperimentReport(
        Instant recordedAt,
        String strategy,
        int initialStock,
        int concurrency,
        int created,
        int rejected,
        long p50Micros,
        long p95Micros,
        long p99Micros,
        long conflicts,
        Map<String, String> environment,
        InvariantSnapshot invariants,
        String verificationStatus) {
}
