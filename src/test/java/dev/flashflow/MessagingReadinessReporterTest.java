package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.messaging.ReadinessStatus;
import dev.flashflow.messaging.spike.MessagingCounts;
import dev.flashflow.verification.messaging.MessagingReadinessReport;
import dev.flashflow.verification.messaging.MessagingReadinessReporter;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagingReadinessReporterTest {
    @TempDir java.nio.file.Path temporary;

    @Test
    void writesAppendOnlyPassingEvidence() throws Exception {
        MessagingReadinessReport report = report(new MessagingCounts(1, 1, 0, 2, 1, 1, 0, 0, 1, 1),
                ReadinessStatus.PASS);
        MessagingReadinessReporter.write(temporary, report);
        assertThat(Files.readString(temporary.resolve("report.md"))).contains("not a production");
        assertThatThrownBy(() -> MessagingReadinessReporter.write(temporary, report))
                .hasMessageContaining("overwrite");
    }

    @Test
    void rejectsPassingReportWhenCountsDoNotReconcile() {
        MessagingReadinessReport invalid = report(
                new MessagingCounts(2, 1, 0, 1, 0, 1, 0, 0, 1, 1), ReadinessStatus.PASS);
        assertThatThrownBy(() -> MessagingReadinessReporter.validate(invalid))
                .hasMessageContaining("must be FAIL");
    }

    @Test
    void preservesBlockedAndNotRunGateMeaning() {
        MessagingCounts counts = new MessagingCounts(1, 1, 0, 1, 0, 1, 0, 0, 1, 1);
        assertThat(MessagingReadinessReporter.validate(report(counts, ReadinessStatus.BLOCKED,
                Map.of("broker", ReadinessStatus.BLOCKED))).status()).isEqualTo(ReadinessStatus.BLOCKED);
        assertThat(MessagingReadinessReporter.validate(report(counts, ReadinessStatus.NOT_RUN,
                Map.of("broker-delay", ReadinessStatus.NOT_RUN))).status()).isEqualTo(ReadinessStatus.NOT_RUN);
    }

    private static MessagingReadinessReport report(MessagingCounts counts, ReadinessStatus status) {
        return report(counts, status, Map.of("contracts", ReadinessStatus.PASS));
    }

    private static MessagingReadinessReport report(
            MessagingCounts counts, ReadinessStatus status, Map<String, ReadinessStatus> gates) {
        return new MessagingReadinessReport("run", "revision", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                "apache/rocketmq:5.3.4", "5.3.4-mqadmin", "single-node", "sync-send-receipt", 2,
                "broker-timestamp-timer", gates, counts, List.of(), status);
    }
}
