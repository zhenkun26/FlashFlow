package dev.flashflow.verification.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class ExperimentEvidenceReporter {
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ExperimentEvidenceReporter() {
    }

    public static ExperimentRunEvidence read(Path runDirectory) throws IOException {
        Properties metadata = properties(runDirectory.resolve("metadata.properties"));
        VerificationStatus gate = VerificationStatus.valueOf(metadata.getProperty("correctnessGate", "NOT_RUN"));
        boolean workloadCompleted = Boolean.parseBoolean(metadata.getProperty("workloadCompleted", "false"));
        boolean collectionComplete = Files.isRegularFile(runDirectory.resolve("k6-summary.json"))
                && Files.isRegularFile(runDirectory.resolve("invariants.tsv"))
                && Files.isRegularFile(runDirectory.resolve("metrics.prom"));

        K6Evidence k6 = readK6(runDirectory.resolve("k6-summary.json"));
        Map<String, String> resolvedInputs = keyValues(runDirectory.resolve("resolved.env"));
        Map<String, Double> operationalMetrics = readOperationalMetrics(runDirectory.resolve("metrics.prom"));
        ExperimentRunEvidence.InvariantEvidence invariants = readInvariants(runDirectory.resolve("invariants.tsv"));
        List<String> warnings = new ArrayList<>();
        boolean dirty = Boolean.parseBoolean(metadata.getProperty("dirtyWorktree", "true"));
        if (dirty) warnings.add("Worktree was dirty; the run is not attributable to the Git revision alone.");
        if (!collectionComplete) warnings.add("Required k6, metrics, or invariant evidence is missing.");
        long classified = k6.outcomes().values().stream().mapToLong(Long::longValue).sum();
        boolean reconciled = classified == k6.totalRequests();
        if (!reconciled) warnings.add("Classified outcomes do not reconcile with total requests.");
        if (invariants == null) warnings.add("Committed-state invariant evidence is unavailable.");
        else if (!invariants.valid()) warnings.add("Committed-state invariants failed.");
        Path diagnosticsError = runDirectory.resolve("mysql-diagnostics-error.log");
        if (Files.isRegularFile(diagnosticsError) && Files.size(diagnosticsError) > 0) {
            warnings.add("Optional MySQL diagnostic capture reported an error; required evidence is unaffected.");
        }

        VerificationStatus status = deriveStatus(gate, workloadCompleted, collectionComplete,
                reconciled, invariants != null && invariants.valid());
        return new ExperimentRunEvidence(
                metadata.getProperty("runId"), metadata.getProperty("caseId"),
                Instant.parse(metadata.getProperty("startedAt")),
                Instant.parse(metadata.getProperty("endedAt", Instant.now().toString())),
                metadata.getProperty("gitRevision", "UNKNOWN"), dirty, gate,
                metadata.getProperty("correctnessGateReference", "none"), workloadCompleted,
                collectionComplete, k6.totalRequests(), k6.outcomes(), k6.latencyMillis(),
                resolvedInputs, operationalMetrics, invariants, status, List.copyOf(warnings), environment(metadata));
    }

    public static VerificationStatus deriveStatus(
            VerificationStatus gate,
            boolean workloadCompleted,
            boolean collectionComplete,
            boolean reconciled,
            boolean invariantsValid) {
        if (gate == VerificationStatus.NOT_RUN) return VerificationStatus.NOT_RUN;
        if (gate == VerificationStatus.BLOCKED) return VerificationStatus.BLOCKED;
        if (gate == VerificationStatus.FAIL) return VerificationStatus.FAIL;
        if (!collectionComplete) return VerificationStatus.BLOCKED;
        if (!workloadCompleted || !reconciled || !invariantsValid) return VerificationStatus.FAIL;
        return VerificationStatus.PASS;
    }

    public static void write(Path runDirectory, ExperimentRunEvidence evidence) throws IOException {
        if (!Files.isDirectory(runDirectory)) {
            throw new IllegalArgumentException("Run directory does not exist: " + runDirectory);
        }
        Path json = runDirectory.resolve("evidence.json");
        Path markdown = runDirectory.resolve("report.md");
        if (Files.exists(json) || Files.exists(markdown)) {
            throw new IllegalStateException("Refusing to overwrite existing report in " + runDirectory);
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), evidence);
        Files.writeString(markdown, markdown(evidence), StandardCharsets.UTF_8);
    }

    public static String comparison(
            ExperimentManifest.Comparison comparison,
            ExperimentRunEvidence left,
            ExperimentRunEvidence right) {
        List<String> differences = new ArrayList<>();
        for (String key : union(left.environment().keySet(), right.environment().keySet())) {
            if (!java.util.Objects.equals(left.environment().get(key), right.environment().get(key))) {
                differences.add(key + ": " + left.environment().get(key) + " -> " + right.environment().get(key));
            }
        }
        String uncontrolled = differences.isEmpty() ? "none" : String.join("; ", differences);
        return "## " + comparison.id() + "\n\n"
                + "- Declared factor: `" + comparison.factor() + "`\n"
                + "- Runs: `" + left.runId() + "` vs `" + right.runId() + "`\n"
                + "- Status: `" + left.status() + "` vs `" + right.status() + "`\n"
                + "- Created: " + left.outcomes().getOrDefault("CREATED", 0L) + " vs "
                + right.outcomes().getOrDefault("CREATED", 0L) + "\n"
                + "- p95: " + left.latencyMillis().getOrDefault("p95", 0.0) + " ms vs "
                + right.latencyMillis().getOrDefault("p95", 0.0) + " ms\n"
                + "- Uncontrolled environment differences: " + uncontrolled + "\n";
    }

    private static K6Evidence readK6(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return new K6Evidence(0, Map.of(), Map.of());
        JsonNode root = JSON.readTree(path.toFile());
        Map<String, Long> outcomes = new LinkedHashMap<>();
        root.path("outcomes").fields().forEachRemaining(entry -> outcomes.put(entry.getKey(), entry.getValue().asLong()));
        Map<String, Double> latency = new LinkedHashMap<>();
        root.path("latencyMillis").fields().forEachRemaining(entry -> latency.put(entry.getKey(), entry.getValue().asDouble()));
        return new K6Evidence(root.path("totalRequests").asLong(), stable(outcomes), stable(latency));
    }

    private static ExperimentRunEvidence.InvariantEvidence readInvariants(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return null;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2) return null;
        String[] headers = lines.get(0).split("\\t");
        String[] values = lines.get(1).split("\\t");
        if (headers.length != values.length) return null;
        Map<String, Long> mapped = new LinkedHashMap<>();
        for (int index = 0; index < headers.length; index++) mapped.put(headers[index], Long.parseLong(values[index]));
        return new ExperimentRunEvidence.InvariantEvidence(
                value(mapped, "initial_stock"), value(mapped, "available_stock"),
                value(mapped, "reserved_stock"), value(mapped, "sold_stock"),
                value(mapped, "effective_orders"), value(mapped, "effective_claims"),
                value(mapped, "reserved_reservations"), value(mapped, "movements"),
                value(mapped, "negative_or_unbalanced_stocks"),
                value(mapped, "effective_orders_without_claims"),
                value(mapped, "claims_without_effective_orders"),
                value(mapped, "order_reservation_mismatches"),
                value(mapped, "duplicate_movement_operations"),
                value(mapped, "effective_orders_over_initial_stock"));
    }

    private static Map<String, String> keyValues(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return Map.of();
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return stable(values);
    }

    private static Map<String, Double> readOperationalMetrics(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return Map.of();
        Map<String, Double> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!(line.startsWith("flashflow_order_attempt")
                    || line.startsWith("flashflow_inventory_conflict")
                    || line.startsWith("hikaricp_connections"))) continue;
            int separator = line.lastIndexOf(' ');
            if (separator <= 0) continue;
            try {
                values.put(line.substring(0, separator), Double.parseDouble(line.substring(separator + 1)));
            } catch (NumberFormatException ignored) {
                // Prometheus metadata and non-numeric samples are not evidence values.
            }
        }
        return stable(values);
    }

    private static Properties properties(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Map<String, String> environment(Properties metadata) {
        Map<String, String> environment = new LinkedHashMap<>();
        metadata.stringPropertyNames().stream().filter(key -> key.startsWith("environment."))
                .sorted().forEach(key -> environment.put(key.substring("environment.".length()), metadata.getProperty(key)));
        return stable(environment);
    }

    private static long value(Map<String, Long> mapped, String key) {
        Long value = mapped.get(key);
        if (value == null) throw new IllegalArgumentException("Missing invariant column: " + key);
        return value;
    }

    private static String markdown(ExperimentRunEvidence evidence) {
        StringBuilder text = new StringBuilder("# FlashFlow experiment ").append(evidence.runId()).append("\n\n");
        text.append("Status: **").append(evidence.status()).append("**\n\n");
        text.append("- Case: `").append(evidence.caseId()).append("`\n");
        text.append("- Revision: `").append(evidence.gitRevision()).append("` (dirty: ")
                .append(evidence.dirtyWorktree()).append(")\n");
        text.append("- Window: ").append(evidence.startedAt()).append(" to ").append(evidence.endedAt()).append("\n");
        text.append("- Correctness gate: ").append(evidence.correctnessGate()).append(" (`")
                .append(evidence.correctnessGateReference()).append("`)\n\n");
        text.append("## Resolved inputs\n\n| Input | Value |\n|---|---|\n");
        evidence.resolvedInputs().forEach((key, value) -> text.append('|').append(key).append('|')
                .append(value).append("|\n"));
        text.append("## Outcomes\n\n| Outcome | Count |\n|---|---:|\n");
        evidence.outcomes().forEach((key, value) -> text.append('|').append(key).append('|').append(value).append("|\n"));
        text.append("\nTotal requests: ").append(evidence.totalRequests()).append("\n\n");
        text.append("## Latency\n\n| Percentile | Milliseconds |\n|---|---:|\n");
        evidence.latencyMillis().forEach((key, value) -> text.append('|').append(key).append('|').append(value).append("|\n"));
        text.append("\n## Retry, conflict, and pool evidence\n\n| Metric | Value |\n|---|---:|\n");
        evidence.operationalMetrics().forEach((key, value) -> text.append('|').append(key).append('|')
                .append(value).append("|\n"));
        text.append("\n## Committed state\n\n");
        if (evidence.invariants() == null) text.append("Invariant evidence unavailable.\n");
        else {
            ExperimentRunEvidence.InvariantEvidence state = evidence.invariants();
            text.append("Stock: `").append(state.initialStock()).append(" = ")
                    .append(state.availableStock()).append(" + ")
                    .append(state.reservedStock()).append(" + ")
                    .append(state.soldStock()).append("`; valid: ")
                    .append(state.valid()).append(".\n\n");
            text.append("| Evidence | Count |\n|---|---:|\n")
                    .append("|Effective orders|").append(state.effectiveOrders()).append("|\n")
                    .append("|Effective claims|").append(state.effectiveClaims()).append("|\n")
                    .append("|Reserved reservations|").append(state.reservedReservations()).append("|\n")
                    .append("|Inventory movements|").append(state.movements()).append("|\n")
                    .append("|Negative or unbalanced stocks|").append(state.negativeOrUnbalancedStocks()).append("|\n")
                    .append("|Effective orders without claims|").append(state.effectiveOrdersWithoutClaims()).append("|\n")
                    .append("|Claims without effective orders|").append(state.claimsWithoutEffectiveOrders()).append("|\n")
                    .append("|Order/reservation mismatches|").append(state.orderReservationMismatches()).append("|\n")
                    .append("|Duplicate movement operations|").append(state.duplicateMovementOperations()).append("|\n")
                    .append("|Effective orders over initial stock|").append(state.effectiveOrdersOverInitialStock()).append("|\n");
        }
        text.append("\nRaw metrics and optional database diagnostics are retained beside this report.\n");
        if (!evidence.warnings().isEmpty()) {
            text.append("\n## Warnings\n\n");
            evidence.warnings().forEach(warning -> text.append("- ").append(warning).append('\n'));
        }
        text.append("\nThis local characterization is not a production QPS, availability, or capacity claim.\n");
        return text.toString();
    }

    private static <T> List<T> union(java.util.Set<T> left, java.util.Set<T> right) {
        List<T> values = new ArrayList<>(left);
        right.stream().filter(value -> !left.contains(value)).forEach(values::add);
        return values;
    }

    private static <K, V> Map<K, V> stable(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private record K6Evidence(long totalRequests, Map<String, Long> outcomes,
                              Map<String, Double> latencyMillis) {
    }
}
