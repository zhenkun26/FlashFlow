package dev.flashflow.ordering;

@FunctionalInterface
public interface OrderingTransactionHook {
    void afterClaimPrecheck(PlaceOrderCommand command);
}
