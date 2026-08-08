package dev.flashflow.shared;

/**
 * Existing-order transactions acquire locks in this order:
 * ORDER -> STOCK -> RESERVATION -> CLAIM_OR_AUXILIARY.
 *
 * <p>The constants are documentation hooks used by architecture tests and code review.
 * New workflows must not introduce a reverse ordering.</p>
 */
public final class LockOrder {
    public static final int ORDER = 10;
    public static final int STOCK = 20;
    public static final int RESERVATION = 30;
    public static final int CLAIM_OR_AUXILIARY = 40;

    private LockOrder() {
    }
}

