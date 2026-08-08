package dev.flashflow.expiration;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class NoOpExpirationTransactionHook implements ExpirationTransactionHook {
    @Override
    public void beforeCommit(List<String> expiredOrderIds) {
        // Test hook: production behavior intentionally does nothing.
    }
}

