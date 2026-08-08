package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;

class ApplicationSmokeTest extends MySqlIntegrationTest {
    @Test
    void contextStartsAndFlywayMigrates() {
        Integer migrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Integer.class);
        assertThat(migrations).isPositive();
        assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualToIgnoringCase("REPEATABLE-READ");
    }
}

