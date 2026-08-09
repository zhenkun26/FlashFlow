package dev.flashflow.ordering;

import org.springframework.stereotype.Component;

@Component
public final class NoOpOrderingTransactionHook implements OrderingTransactionHook {
    @Override
    public void afterClaimPrecheck(PlaceOrderCommand command) {
        // Production ordering has no artificial interleaving boundary.
    }
}
