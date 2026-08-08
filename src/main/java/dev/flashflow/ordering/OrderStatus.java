package dev.flashflow.ordering;

public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    CLOSED_UNPAID;

    public boolean canTransitionTo(OrderStatus target) {
        return this == PENDING_PAYMENT && (target == PAID || target == CLOSED_UNPAID);
    }

    public boolean isEffective() {
        return this == PENDING_PAYMENT || this == PAID;
    }
}

