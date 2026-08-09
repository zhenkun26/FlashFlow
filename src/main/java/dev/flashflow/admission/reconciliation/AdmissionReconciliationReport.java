package dev.flashflow.admission.reconciliation;

import dev.flashflow.admission.AdmissionGenerationSnapshot;
import java.time.Instant;
import java.util.Map;

public record AdmissionReconciliationReport(
        String runId,
        String skuId,
        String sourceGeneration,
        String targetGeneration,
        Instant startedAt,
        Instant snapshotBoundary,
        Instant completedAt,
        ReconciliationStatus status,
        Map<String, Long> discrepancies,
        Map<String, Long> actions,
        long unresolved,
        AdmissionGenerationSnapshot before,
        AdmissionGenerationSnapshot after,
        String message) {
}
