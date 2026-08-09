package dev.flashflow.shared.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AdmissionConfigurationGuardTest {
    @Test
    void redisModeRequiresLongSecret() {
        AdmissionConfigurationGuard guard = new AdmissionConfigurationGuard(properties(
                new FlashFlowProperties.Admission(
                        FlashFlowProperties.AdmissionMode.REDIS_LUA, Duration.ofSeconds(30), "v2-1", "short")));
        assertThatThrownBy(guard::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void heldResolutionIsBounded() {
        AdmissionConfigurationGuard guard = new AdmissionConfigurationGuard(properties(
                new FlashFlowProperties.Admission(
                        FlashFlowProperties.AdmissionMode.MYSQL_ONLY, Duration.ofMillis(1), "v2-1", "")));
        assertThatThrownBy(guard::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("held-resolution");
    }

    private static FlashFlowProperties properties(FlashFlowProperties.Admission admission) {
        return new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 3),
                new FlashFlowProperties.Ordering(3, 1, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 10, false), admission);
    }
}
