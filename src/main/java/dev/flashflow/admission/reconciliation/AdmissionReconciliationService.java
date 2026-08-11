package dev.flashflow.admission.reconciliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.admission.AdmissionGenerationSnapshot;
import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.admission.AdmissionRecordView;
import dev.flashflow.admission.AdmissionState;
import dev.flashflow.admission.persistence.CommandAdmissionRow;
import dev.flashflow.ordering.persistence.IdempotencyRow;
import dev.flashflow.shared.FlashFlowMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdmissionReconciliationService {
    private final MySqlAdmissionSnapshotService mysql;
    private final AdmissionAdministrationPort admission;
    private final AdmissionIdentity identities;
    private final FlashFlowMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdmissionReconciliationService(
            MySqlAdmissionSnapshotService mysql,
            AdmissionAdministrationPort admission,
            AdmissionIdentity identities,
            FlashFlowMetrics metrics,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mysql = mysql;
        this.admission = admission;
        this.identities = identities;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AdmissionReconciliationReport reconcile(String skuId, Path outputDirectory) {
        return reconcile(skuId, outputDirectory, "r-" + UUID.randomUUID());
    }

    public AdmissionReconciliationReport reconcile(
            String skuId, Path outputDirectory, String generation) {
        if (generation == null || !generation.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalArgumentException("generation must contain only bounded key-safe characters");
        }
        Instant started = clock.instant();
        String runId = started.toString().replace(":", "").replace("-", "") + "-" + UUID.randomUUID();
        AdmissionGenerationSnapshot before;
        List<AdmissionRecordView> oldRecords;
        try {
            before = admission.snapshot(skuId);
            oldRecords = admission.records(skuId);
        } catch (RuntimeException exception) {
            AdmissionGenerationSnapshot unavailable = missingSnapshot();
            return persist(outputDirectory, new AdmissionReconciliationReport(
                    runId, skuId, null, generation, started, null, clock.instant(),
                    ReconciliationStatus.BLOCKED, Map.of("REDIS_UNAVAILABLE", 1L), Map.of(),
                    0, unavailable, unavailable,
                    "Redis admission evidence unavailable: " + exception.getClass().getSimpleName()));
        }
        String fence = UUID.randomUUID().toString();
        boolean fenced;
        try {
            fenced = admission.beginGeneration(skuId, generation, 0, fence);
        } catch (RuntimeException exception) {
            return redisBlocked(outputDirectory, runId, skuId, generation, started, null,
                    before, Map.of(), Map.of(), 0, "Redis maintenance fence unavailable", exception);
        }
        if (!fenced) {
            return persist(outputDirectory, report(runId, skuId, before, generation, started, null,
                    ReconciliationStatus.BLOCKED, Map.of(), Map.of(), 0,
                    "Maintenance fence is busy"));
        }
        MySqlAdmissionFacts facts;
        try {
            facts = mysql.capture(skuId);
        } catch (RuntimeException exception) {
            return persist(outputDirectory, report(runId, skuId, before, null, started, null,
                    ReconciliationStatus.BLOCKED, Map.of(), Map.of(), 0,
                    "MySQL snapshot unavailable: " + exception.getClass().getSimpleName()));
        }

        Map<String, IdempotencyRow> idempotencyByAdmission = new HashMap<>();
        for (IdempotencyRow row : facts.idempotencyRows()) {
            idempotencyByAdmission.put(identities.admissionId(
                    row.operationName(), row.callerId(), row.idempotencyKey()), row);
        }
        Map<String, CommandAdmissionRow> commandByAdmission = new HashMap<>();
        facts.commands().forEach(command -> commandByAdmission.put(command.commandId(), command));

        Map<String, Long> discrepancies = new LinkedHashMap<>();
        Map<String, Long> actions = new LinkedHashMap<>();
        List<AdmissionRecordView> seeds = new ArrayList<>();
        Map<String, Boolean> consumes = new HashMap<>();
        Map<String, Boolean> effectiveUsers = new HashMap<>();
        Map<String, AdmissionRecordView> oldByAdmission = new HashMap<>();
        oldRecords.forEach(record -> oldByAdmission.put(record.admissionId(), record));
        if (before.generation() == null) {
            increment(discrepancies, "MISSING_GENERATION");
        } else if (before.state() != dev.flashflow.admission.AdmissionGenerationState.READY) {
            increment(discrepancies, "STALE_GENERATION");
        }

        facts.effectiveOrders().forEach(order -> {
            String admissionId = order.idempotencyKey() == null
                    ? identities.admissionId("RECONCILED_ORDER", order.userId(), order.orderId())
                    : identities.admissionId("CREATE_ORDER", order.callerId(), order.idempotencyKey());
            String userDigest = identities.userDigest(skuId, order.userId());
            seeds.add(new AdmissionRecordView(admissionId, userDigest, AdmissionState.CONFIRMED, Instant.EPOCH));
            consumes.put(admissionId, false);
            effectiveUsers.put(userDigest, true);
            AdmissionRecordView old = oldByAdmission.get(admissionId);
            if (old == null || old.state() != AdmissionState.CONFIRMED) {
                increment(discrepancies, "MISSING_CONFIRMATION");
            }
        });

        long unresolved = 0;
        for (AdmissionRecordView record : oldRecords) {
            if (effectiveUsers.containsKey(record.userDigest())) continue;
            IdempotencyRow durable = idempotencyByAdmission.get(record.admissionId());
            CommandAdmissionRow command = commandByAdmission.get(record.admissionId());
            if (record.state() == AdmissionState.CONFIRMED) {
                increment(discrepancies, "ORPHANED_CONFIRMED");
                increment(actions, "DROP_STALE");
            } else if (record.state() == AdmissionState.RELEASED) {
                increment(actions, "DROP_TERMINAL");
            } else if (durable != null && "COMPLETED".equals(durable.status())) {
                increment(discrepancies, "STALE_NON_EFFECTIVE");
                increment(actions, "RELEASE_PROVEN");
            } else {
                unresolved++;
                increment(discrepancies, commandClassification(command));
                seeds.add(new AdmissionRecordView(record.admissionId(), record.userDigest(),
                        AdmissionState.QUARANTINED, record.resolutionDeadline()));
                consumes.put(record.admissionId(), true);
                increment(actions, "WITHHOLD_CAPACITY");
            }
        }

        long expectedAvailable = facts.stock().availableStock();
        if (before.remainingCapacity() > expectedAvailable) {
            discrepancies.put("EXCESS_CAPACITY", (long) before.remainingCapacity() - expectedAvailable);
        } else if (before.remainingCapacity() < expectedAvailable) {
            discrepancies.put("MISSING_CAPACITY", expectedAvailable - before.remainingCapacity());
        }

        boolean initialized;
        try {
            initialized = admission.beginGeneration(
                    skuId, generation, facts.stock().availableStock(), fence);
        } catch (RuntimeException exception) {
            return redisBlocked(outputDirectory, runId, skuId, generation, started,
                    facts.snapshotBoundary(), before, discrepancies, actions, unresolved,
                    "Redis generation initialization unavailable", exception);
        }
        if (!initialized) {
            return persist(outputDirectory, report(runId, skuId, before, generation, started,
                    facts.snapshotBoundary(), ReconciliationStatus.BLOCKED,
                    discrepancies, actions, unresolved, "Maintenance fence ownership was lost"));
        }

        boolean redisFailure = false;
        for (AdmissionRecordView seed : seeds) {
            boolean seeded;
            try {
                seeded = admission.seed(
                        skuId, generation, fence, seed, consumes.get(seed.admissionId()));
            } catch (RuntimeException exception) {
                redisFailure = true;
                increment(discrepancies, "REDIS_UNAVAILABLE");
                break;
            }
            if (!seeded) {
                unresolved++;
                increment(discrepancies, "SEED_REJECTED");
            } else {
                increment(actions, seed.state() == AdmissionState.CONFIRMED
                        ? "SEED_CONFIRMED" : "SEED_QUARANTINED");
            }
        }

        ReconciliationStatus status;
        String message;
        if (redisFailure) {
            status = ReconciliationStatus.BLOCKED;
            message = "Redis failed while seeding the unpublished replacement generation";
        } else if (unresolved > 0) {
            status = ReconciliationStatus.BLOCKED;
            message = "Unresolved admissions retained behind an unpublished generation";
        } else {
            try {
                if (!admission.publishGeneration(skuId, generation, fence)) {
                    status = ReconciliationStatus.BLOCKED;
                    message = "Generation publication fence was lost";
                } else {
                    status = ReconciliationStatus.PASS;
                    message = "Redis generation converged from committed MySQL facts";
                }
            } catch (RuntimeException exception) {
                status = ReconciliationStatus.BLOCKED;
                message = "Redis generation publication was unavailable: "
                        + exception.getClass().getSimpleName();
                increment(discrepancies, "REDIS_UNAVAILABLE");
            }
        }
        AdmissionGenerationSnapshot after;
        try {
            after = admission.snapshot(skuId);
        } catch (RuntimeException exception) {
            after = before;
            status = ReconciliationStatus.BLOCKED;
            message = "Final Redis evidence unavailable: " + exception.getClass().getSimpleName();
            increment(discrepancies, "REDIS_UNAVAILABLE");
        }
        discrepancies.forEach((key, value) -> metrics.reconciliation(key, "DETECTED"));
        actions.forEach((key, value) -> metrics.reconciliation(key, "APPLIED"));
        metrics.reconciliation("RUN", status.name());
        return persist(outputDirectory, new AdmissionReconciliationReport(runId, skuId,
                before.generation(), generation, started, facts.snapshotBoundary(), clock.instant(), status,
                Map.copyOf(discrepancies), Map.copyOf(actions), unresolved, before, after, message));
    }

    private AdmissionReconciliationReport report(
            String runId, String skuId, AdmissionGenerationSnapshot before, String target,
            Instant started, Instant boundary, ReconciliationStatus status,
            Map<String, Long> discrepancies, Map<String, Long> actions, long unresolved, String message) {
        return new AdmissionReconciliationReport(runId, skuId, before.generation(), target,
                started, boundary, clock.instant(), status, discrepancies, actions, unresolved,
                before, admission.snapshot(skuId), message);
    }

    private AdmissionReconciliationReport persist(Path directory, AdmissionReconciliationReport report) {
        try {
            Files.createDirectories(directory);
            Path output = directory.resolve(report.runId() + ".json");
            try (var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(stream, report);
            }
            return report;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot persist append-only reconciliation report", exception);
        }
    }

    private AdmissionReconciliationReport redisBlocked(
            Path outputDirectory, String runId, String skuId, String generation,
            Instant started, Instant boundary, AdmissionGenerationSnapshot before,
            Map<String, Long> discrepancies, Map<String, Long> actions, long unresolved,
            String message, RuntimeException exception) {
        Map<String, Long> bounded = new LinkedHashMap<>(discrepancies);
        increment(bounded, "REDIS_UNAVAILABLE");
        return persist(outputDirectory, new AdmissionReconciliationReport(
                runId, skuId, before.generation(), generation, started, boundary, clock.instant(),
                ReconciliationStatus.BLOCKED, Map.copyOf(bounded), Map.copyOf(actions), unresolved,
                before, before, message + ": " + exception.getClass().getSimpleName()));
    }

    private static AdmissionGenerationSnapshot missingSnapshot() {
        return new AdmissionGenerationSnapshot(null,
                dev.flashflow.admission.AdmissionGenerationState.MISSING, 0, 0, 0, 0, 0, 0);
    }

    private static void increment(Map<String, Long> values, String key) {
        values.merge(key, 1L, Long::sum);
    }

    private static String commandClassification(CommandAdmissionRow command) {
        if (command == null) return "AMBIGUOUS_HELD";
        if (command.deadLetteredAt() != null) return "DEAD_LETTERED_WITHOUT_MYSQL_RESULT";
        if (command.outboxStatus() != null && !"ACCEPTED".equals(command.status())
                && !"PROCESSING".equals(command.status())) {
            return "CONTRADICTORY_COMMAND_OUTBOX";
        }
        if ("OUTBOX_COMMITTED".equals(command.transportCause()) && command.outboxStatus() == null) {
            return "MISSING_OUTBOX_FOR_ACCEPTED_COMMAND";
        }
        if (command.outboxStatus() != null) {
            return switch (command.outboxStatus()) {
                case "READY" -> "OUTBOX_READY_IN_FLIGHT";
                case "CLAIMED" -> "OUTBOX_CLAIMED_IN_FLIGHT";
                case "RETRYABLE" -> "OUTBOX_RETRYABLE_IN_FLIGHT";
                case "ACKNOWLEDGED" -> "OUTBOX_ACKNOWLEDGED_AWAITING_RESULT";
                case "INVALID" -> "OUTBOX_INVALID_OPERATOR_REQUIRED";
                case "EXHAUSTED" -> "OUTBOX_EXHAUSTED_OPERATOR_REQUIRED";
                default -> "UNKNOWN_OUTBOX_DISPOSITION";
            };
        }
        return switch (command.status()) {
            case "PREPARED" -> "AGED_PREPARED_COMMAND";
            case "UNRESOLVED" -> "AMBIGUOUS_COMMAND";
            case "RETRYABLE" -> "RETRYABLE_COMMAND";
            case "ACCEPTED", "PROCESSING" -> "IN_FLIGHT_COMMAND";
            default -> "AMBIGUOUS_HELD";
        };
    }
}
