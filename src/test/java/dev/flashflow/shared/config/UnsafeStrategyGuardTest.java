package dev.flashflow.shared.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class UnsafeStrategyGuardTest {
    @Test
    void rejectsUnsafeStrategyWithoutLabProfile() {
        FlashFlowProperties properties = new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.UNSAFE_READ_THEN_WRITE, 3),
                new FlashFlowProperties.Ordering(3, 1, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 10, false),
                mysqlOnlyAdmission());
        UnsafeStrategyGuard guard = new UnsafeStrategyGuard(properties, new MockEnvironment());

        assertThatThrownBy(guard::rejectUnsafeNormalRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab profile");
    }

    @Test
    void rejectsLegacyChildFirstSequenceWithoutLabProfile() {
        FlashFlowProperties properties = new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 3),
                new FlashFlowProperties.Ordering(
                        3, 1, FlashFlowProperties.TransactionSequence.CHILD_FIRST_LEGACY),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 10, false),
                mysqlOnlyAdmission());

        assertThatThrownBy(() -> new UnsafeStrategyGuard(properties, new MockEnvironment())
                .rejectUnsafeNormalRuntime())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHILD_FIRST_LEGACY")
                .hasMessageContaining("lab profile");
    }

    private static FlashFlowProperties.Admission mysqlOnlyAdmission() {
        return new FlashFlowProperties.Admission(
                FlashFlowProperties.AdmissionMode.MYSQL_ONLY, Duration.ofSeconds(30), "v2-1", "");
    }
}
