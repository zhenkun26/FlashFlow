package dev.flashflow.verification.experiment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExperimentRunEvidence(
        String runId,
        String caseId,
        Instant startedAt,
        Instant endedAt,
        String gitRevision,
        boolean dirtyWorktree,
        VerificationStatus correctnessGate,
        String correctnessGateReference,
        boolean workloadCompleted,
        boolean collectionComplete,
        long totalRequests,
        Map<String, Long> outcomes,
        Map<String, Double> latencyMillis,
        Map<String, String> resolvedInputs,
        Map<String, Double> operationalMetrics,
        Map<String, String> admissionEvidence,
        InvariantEvidence invariants,
        VerificationStatus status,
        List<String> warnings,
        Map<String, String> environment) {

    public record InvariantEvidence(
            long initialStock,
            long availableStock,
            long reservedStock,
            long soldStock,
            long effectiveOrders,
            long effectiveClaims,
            long reservedReservations,
            long movements,
            long negativeOrUnbalancedStocks,
            long effectiveOrdersWithoutClaims,
            long claimsWithoutEffectiveOrders,
            long orderReservationMismatches,
            long duplicateMovementOperations,
            long effectiveOrdersOverInitialStock) {

        public boolean valid() {
            return initialStock == availableStock + reservedStock + soldStock
                    && negativeOrUnbalancedStocks == 0
                    && effectiveOrdersWithoutClaims == 0
                    && claimsWithoutEffectiveOrders == 0
                    && orderReservationMismatches == 0
                    && duplicateMovementOperations == 0
                    && effectiveOrdersOverInitialStock == 0;
        }
    }
}
