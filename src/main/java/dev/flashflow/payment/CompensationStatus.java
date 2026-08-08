package dev.flashflow.payment;

public enum CompensationStatus {
    OPEN,
    RESOLVED;

    public boolean canTransitionTo(CompensationStatus target) {
        return this == OPEN && target == RESOLVED;
    }
}
