package dev.flashflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flashflow.verification.experiment.ExperimentEvidenceReporter;
import dev.flashflow.verification.experiment.ExperimentManifest;
import dev.flashflow.verification.experiment.ExperimentRunEvidence;
import dev.flashflow.verification.experiment.VerificationStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExperimentEvidenceReporterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void readsReconcilesAndWritesAppendOnlyEvidence() throws Exception {
        Path run = Files.createDirectory(temporaryDirectory.resolve("run-1"));
        Files.writeString(run.resolve("metadata.properties"), """
                runId=run-1
                caseId=baseline
                startedAt=2026-08-09T00:00:00Z
                endedAt=2026-08-09T00:00:05Z
                gitRevision=abc123
                dirtyWorktree=false
                correctnessGate=PASS
                correctnessGateReference=gate.log
                workloadCompleted=true
                environment.java=21
                environment.database=MySQL-8.4
                """);
        Files.writeString(run.resolve("k6-summary.json"), """
                {"totalRequests":10,"outcomes":{"CREATED":4,"SOLD_OUT":5,"RETRYABLE_CONTENTION":1,"UNEXPECTED":0},"latencyMillis":{"mean":12.5,"p90":20.0,"p95":25.0,"p99":30.0,"max":35.0}}
                """);
        Files.writeString(run.resolve("invariants.tsv"), invariantHeader() + "\n"
                + "10\t6\t4\t0\t4\t4\t4\t4\t0\t0\t0\t0\t0\t0\n");
        Files.writeString(run.resolve("resolved.env"), "CASE_ID=baseline\nVUS=10\n");
        Files.writeString(run.resolve("metrics.prom"), """
                flashflow_order_attempt_total{outcome="STARTED",strategy="CONDITIONAL_ATOMIC"} 10
                flashflow_admission_decision_total{decision="BYPASSED"} 10
                flashflow_admission_mysql_total{outcome="STARTED"} 10
                flashflow_admission_lifecycle_total{operation="CONFIRM",outcome="BYPASSED"} 10
                hikaricp_connections_timeout_total{pool="HikariPool-1"} 0
                """);

        ExperimentRunEvidence evidence = ExperimentEvidenceReporter.read(run);
        assertThat(evidence.status()).isEqualTo(VerificationStatus.PASS);
        assertThat(evidence.warnings()).isEmpty();
        assertThat(evidence.resolvedInputs()).containsEntry("VUS", "10");
        assertThat(evidence.operationalMetrics()).hasSize(5);
        ExperimentEvidenceReporter.write(run, evidence);
        assertThat(run.resolve("report.md")).content().contains("Status: **PASS**")
                .contains("Retry, conflict, and pool evidence")
                .contains("not a production QPS");
        assertThat(run.resolve("evidence.json")).exists();
        assertThatThrownBy(() -> ExperimentEvidenceReporter.write(run, evidence))
                .hasMessageContaining("Refusing to overwrite");
    }

    @Test
    void derivesFailBlockedAndNotRunWithoutInflatingEvidence() {
        assertThat(ExperimentEvidenceReporter.deriveStatus(
                VerificationStatus.PASS, true, true, true, false)).isEqualTo(VerificationStatus.FAIL);
        assertThat(ExperimentEvidenceReporter.deriveStatus(
                VerificationStatus.PASS, true, false, true, true)).isEqualTo(VerificationStatus.BLOCKED);
        assertThat(ExperimentEvidenceReporter.deriveStatus(
                VerificationStatus.FAIL, false, false, false, false)).isEqualTo(VerificationStatus.FAIL);
        assertThat(ExperimentEvidenceReporter.deriveStatus(
                VerificationStatus.NOT_RUN, false, false, false, false)).isEqualTo(VerificationStatus.NOT_RUN);
    }

    @Test
    void requiresIdentityLevelMessagingReconciliationForLiveRuns() throws Exception {
        Path run = Files.createDirectory(temporaryDirectory.resolve("live"));
        Files.writeString(run.resolve("metadata.properties"), """
                runId=live
                caseId=rocketmq-live-v3
                startedAt=2026-08-10T00:00:00Z
                endedAt=2026-08-10T00:00:05Z
                gitRevision=abc
                dirtyWorktree=false
                correctnessGate=PASS
                workloadCompleted=true
                """);
        Files.writeString(run.resolve("k6-summary.json"), """
                {"totalRequests":1,"outcomes":{"ACCEPTED":1},"latencyMillis":{"p95":20.0}}
                """);
        Files.writeString(run.resolve("invariants.tsv"), invariantHeader() + "\n"
                + "1\t0\t1\t0\t1\t1\t1\t1\t0\t0\t0\t0\t0\t0\n");
        Files.writeString(run.resolve("resolved.env"), "MESSAGING_MODE=LIVE\n");
        Files.writeString(run.resolve("metrics.prom"), """
                flashflow_admission_decision_total{decision="BYPASSED"} 1
                flashflow_admission_mysql_total{outcome="STARTED"} 1
                flashflow_admission_lifecycle_total{operation="CONFIRM",outcome="BYPASSED"} 1
                flashflow_command_publication_total{outcome="ACKNOWLEDGED"} 1
                """);
        Files.writeString(run.resolve("messaging-evidence.properties"), """
                identity.http=1
                identity.prepared=1
                identity.accepted=1
                identity.completed=1
                identity.rejected=0
                identity.retryable=0
                identity.unresolved=0
                identity.inFlight=0
                delivery.inFlight=0
                expiration.inFlight=0
                mysql.orders=1
                required.fail=0
                required.blocked=0
                required.notRun=0
                required.simulated=0
                required.stale=0
                """);

        ExperimentRunEvidence evidence = ExperimentEvidenceReporter.read(run);
        assertThat(evidence.status()).isEqualTo(VerificationStatus.PASS);
        assertThat(evidence.messagingEvidence()).containsEntry("identity.completed", "1");

        Files.writeString(run.resolve("messaging-evidence.properties"),
                Files.readString(run.resolve("messaging-evidence.properties"))
                        .replace("delivery.inFlight=0", "delivery.inFlight=1"));
        ExperimentRunEvidence inFlight = ExperimentEvidenceReporter.read(run);
        assertThat(inFlight.status()).isEqualTo(VerificationStatus.FAIL);
        assertThat(inFlight.warnings()).anyMatch(warning -> warning.contains("does not reconcile"));
    }

    @Test
    void reportsDirtyAttributionAndControlledComparisonDifferences() throws Exception {
        ExperimentRunEvidence left = evidence("left", false, Map.of("java", "21", "database", "MySQL-8.4"));
        ExperimentRunEvidence right = evidence("right", true, Map.of("java", "26", "database", "MySQL-8.4"));
        ExperimentManifest.Comparison comparison = new ExperimentManifest.Comparison(
                "compare-vus", ExperimentManifest.Factor.VUS, List.of("left", "right"));

        assertThat(ExperimentEvidenceReporter.comparison(comparison, left, right))
                .contains("Declared factor: `VUS`")
                .contains("java: 21 -> 26");

        Path dirtyRun = Files.createDirectory(temporaryDirectory.resolve("dirty"));
        Files.writeString(dirtyRun.resolve("metadata.properties"), """
                runId=dirty
                caseId=baseline
                startedAt=2026-08-09T00:00:00Z
                dirtyWorktree=true
                correctnessGate=PASS
                workloadCompleted=true
                """);
        ExperimentRunEvidence dirty = ExperimentEvidenceReporter.read(dirtyRun);
        assertThat(dirty.status()).isEqualTo(VerificationStatus.BLOCKED);
        assertThat(dirty.warnings()).anyMatch(warning -> warning.contains("dirty"));
    }

    private static ExperimentRunEvidence evidence(String runId, boolean dirty, Map<String, String> environment) {
        ExperimentRunEvidence.InvariantEvidence invariants = new ExperimentRunEvidence.InvariantEvidence(
                10, 5, 5, 0, 5, 5, 5, 5, 0, 0, 0, 0, 0, 0);
        return new ExperimentRunEvidence(runId, runId, Instant.EPOCH, Instant.EPOCH.plusSeconds(5),
                "abc", dirty, VerificationStatus.PASS, "gate.log", true, true, 10,
                Map.of("CREATED", 5L, "SOLD_OUT", 5L), Map.of("p95", 20.0),
                Map.of("VUS", "10"), Map.of("flashflow_order_attempt_total", 10.0),
                Map.of(), Map.of(), invariants, VerificationStatus.PASS, List.of(), environment);
    }

    private static String invariantHeader() {
        return "initial_stock\tavailable_stock\treserved_stock\tsold_stock\teffective_orders\t"
                + "effective_claims\treserved_reservations\tmovements\tnegative_or_unbalanced_stocks\t"
                + "effective_orders_without_claims\tclaims_without_effective_orders\t"
                + "order_reservation_mismatches\tduplicate_movement_operations\t"
                + "effective_orders_over_initial_stock";
    }
}
