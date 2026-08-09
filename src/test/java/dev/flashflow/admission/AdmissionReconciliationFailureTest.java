package dev.flashflow.admission;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flashflow.admission.reconciliation.AdmissionReconciliationService;
import dev.flashflow.admission.reconciliation.MySqlAdmissionSnapshotService;
import dev.flashflow.admission.reconciliation.ReconciliationStatus;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.RedisConnectionFailureException;

class AdmissionReconciliationFailureTest {
    @TempDir Path reports;

    @Test
    void redisOutageStillProducesAppendOnlyBlockedEvidence() throws Exception {
        AdmissionAdministrationPort admission = unavailableAdmission();
        FlashFlowProperties properties = new FlashFlowProperties(
                new FlashFlowProperties.Inventory(FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 0),
                new FlashFlowProperties.Ordering(
                        0, 0, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(10), 10, false),
                new FlashFlowProperties.Admission(
                        FlashFlowProperties.AdmissionMode.REDIS_LUA, Duration.ofSeconds(30),
                        "v2-1", "integration-test-admission-secret-32-characters"));
        AdmissionReconciliationService service = new AdmissionReconciliationService(
                new MySqlAdmissionSnapshotService(null, Clock.systemUTC()), admission,
                new AdmissionIdentity(properties), new FlashFlowMetrics(new SimpleMeterRegistry()),
                new ObjectMapper().findAndRegisterModules(), Clock.systemUTC());

        var report = service.reconcile("s1", reports);

        assertThat(report.status()).isEqualTo(ReconciliationStatus.BLOCKED);
        assertThat(report.discrepancies()).containsEntry("REDIS_UNAVAILABLE", 1L);
        try (var files = Files.list(reports)) {
            assertThat(files).hasSize(1);
        }
    }

    private static AdmissionAdministrationPort unavailableAdmission() {
        return new AdmissionAdministrationPort() {
            @Override public boolean beginGeneration(
                    String skuId, String generation, int capacity, String fenceToken) { return false; }
            @Override public boolean publishGeneration(
                    String skuId, String generation, String fenceToken) { return false; }
            @Override public AdmissionGenerationSnapshot snapshot(String skuId) {
                throw new RedisConnectionFailureException("offline");
            }
            @Override public List<AdmissionRecordView> records(String skuId) { return List.of(); }
            @Override public boolean seed(
                    String skuId, String generation, String fenceToken,
                    AdmissionRecordView record, boolean consumeCapacity) { return false; }
        };
    }
}
