package dev.flashflow.verification.persistence;

public record InvariantSnapshot(
        long negativeOrUnbalancedStocks,
        long effectiveOrdersWithoutClaims,
        long claimsWithoutEffectiveOrders,
        long orderReservationMismatches,
        long duplicateMovementOperations,
        long effectiveOrdersOverInitialStock) {

    public boolean valid() {
        return negativeOrUnbalancedStocks == 0
                && effectiveOrdersWithoutClaims == 0
                && claimsWithoutEffectiveOrders == 0
                && orderReservationMismatches == 0
                && duplicateMovementOperations == 0
                && effectiveOrdersOverInitialStock == 0;
    }
}

