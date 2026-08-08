package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flashflow.verification.ExperimentReport;
import dev.flashflow.verification.ExperimentReporter;
import dev.flashflow.verification.persistence.InvariantSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExperimentReporterTest {
    @Test
    void recordsCountsPercentilesEnvironmentAndInvariants() {
        InvariantSnapshot invariants = new InvariantSnapshot(0, 0, 0, 0, 0, 0);
        ExperimentReport report = ExperimentReporter.report(
                "CONDITIONAL_ATOMIC", 10, 100, 10, 3,
                List.of(10L, 20L, 30L, 40L, 50L),
                Map.of("java", "21", "database", "MySQL 8.4"), invariants, "PASS");

        assertThat(report.rejected()).isEqualTo(90);
        assertThat(report.p50Micros()).isEqualTo(30);
        assertThat(report.p95Micros()).isEqualTo(50);
        assertThat(report.invariants().valid()).isTrue();
    }
}
