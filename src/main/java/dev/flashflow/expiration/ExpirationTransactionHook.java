package dev.flashflow.expiration;

import java.util.List;

@FunctionalInterface
public interface ExpirationTransactionHook {
    void beforeCommit(List<String> expiredOrderIds);
}

