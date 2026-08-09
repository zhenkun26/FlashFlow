package dev.flashflow.shared.config;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class UnsafeStrategyGuard {
    private final FlashFlowProperties properties;
    private final Environment environment;

    public UnsafeStrategyGuard(FlashFlowProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void rejectUnsafeNormalRuntime() {
        boolean lab = Arrays.asList(environment.getActiveProfiles()).contains("lab");
        if (properties.inventory().strategy() == FlashFlowProperties.Strategy.UNSAFE_READ_THEN_WRITE && !lab) {
            throw new IllegalStateException("UNSAFE_READ_THEN_WRITE is restricted to the lab profile");
        }
        if (properties.ordering().transactionSequence()
                == FlashFlowProperties.TransactionSequence.CHILD_FIRST_LEGACY && !lab) {
            throw new IllegalStateException("CHILD_FIRST_LEGACY is restricted to the lab profile");
        }
    }
}
