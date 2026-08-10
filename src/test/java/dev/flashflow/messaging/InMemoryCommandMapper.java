package dev.flashflow.messaging;

import dev.flashflow.messaging.persistence.CommandMapper;
import dev.flashflow.messaging.persistence.CommandRow;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

final class InMemoryCommandMapper implements CommandMapper {
    private final Map<String, CommandRow> rows = new LinkedHashMap<>();

    @Override
    public int tryInsert(String id, String caller, String key, String sku, String fingerprint,
                         int version, LocalDateTime now) {
        if (rows.containsKey(id)) return 0;
        rows.put(id, new CommandRow(id, "CREATE_ORDER", caller, key, sku, fingerprint, version,
                "PREPARED", null, null, 0, 0, null, now, now, null, null));
        return 1;
    }

    @Override public CommandRow findById(String id) { return rows.get(id); }

    @Override
    public CommandRow findByIdAndCaller(String id, String caller) {
        CommandRow row = rows.get(id);
        return row != null && row.callerId().equals(caller) ? row : null;
    }

    @Override
    public int claim(String id, LocalDateTime now) {
        CommandRow row = rows.get(id);
        if (row == null || terminal(row)) return 0;
        replace(row, "PROCESSING", row.resultCode(), row.orderId(), row.attemptCount() + 1,
                row.publicationAttemptCount(), row.transportCause(), now, row.completedAt(), row.deadLetteredAt());
        return 1;
    }

    @Override
    public int finish(String id, String status, String code, String orderId, LocalDateTime now) {
        CommandRow row = rows.get(id);
        if (row == null || !"PROCESSING".equals(row.status())) return 0;
        replace(row, status, code, orderId, row.attemptCount(), row.publicationAttemptCount(),
                row.transportCause(), now, now, row.deadLetteredAt());
        return 1;
    }

    @Override
    public int markNonTerminal(String id, String status, String cause, int publicationAttempt, LocalDateTime now) {
        CommandRow row = rows.get(id);
        if (row == null || terminal(row)) return 0;
        replace(row, status, row.resultCode(), row.orderId(), row.attemptCount(),
                row.publicationAttemptCount() + publicationAttempt, cause, now,
                row.completedAt(), row.deadLetteredAt());
        return 1;
    }

    @Override
    public int markDeadLetter(String id, String cause, LocalDateTime now) {
        CommandRow row = rows.get(id);
        if (row == null || terminal(row)) return 0;
        replace(row, "RETRYABLE", row.resultCode(), row.orderId(), row.attemptCount(),
                row.publicationAttemptCount(), cause, now, row.completedAt(), now);
        return 1;
    }

    private void replace(CommandRow row, String status, String resultCode, String orderId,
                         int attempts, int publicationAttempts, String cause, LocalDateTime updatedAt,
                         LocalDateTime completedAt, LocalDateTime deadLetteredAt) {
        rows.put(row.commandId(), new CommandRow(row.commandId(), row.operationName(), row.callerId(),
                row.idempotencyKey(), row.activitySkuId(), row.payloadFingerprint(), row.schemaVersion(),
                status, resultCode, orderId, attempts, publicationAttempts, cause,
                row.createdAt(), updatedAt, completedAt, deadLetteredAt));
    }

    private static boolean terminal(CommandRow row) {
        return "COMPLETED".equals(row.status()) || "REJECTED".equals(row.status());
    }
}
