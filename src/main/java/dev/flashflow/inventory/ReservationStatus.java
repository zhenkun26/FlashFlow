package dev.flashflow.inventory;

public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED;

    public boolean canTransitionTo(ReservationStatus target) {
        return this == RESERVED && (target == CONFIRMED || target == RELEASED);
    }
}

