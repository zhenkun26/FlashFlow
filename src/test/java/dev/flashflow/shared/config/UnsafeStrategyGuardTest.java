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
                new FlashFlowProperties.Ordering(3),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 10, false));
        UnsafeStrategyGuard guard = new UnsafeStrategyGuard(properties, new MockEnvironment());

        assertThatThrownBy(guard::rejectUnsafeNormalRuntime)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lab profile");
    }
}
