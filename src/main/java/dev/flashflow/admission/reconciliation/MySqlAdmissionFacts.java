package dev.flashflow.admission.reconciliation;

import dev.flashflow.admission.persistence.AdmissionStockRow;
import dev.flashflow.admission.persistence.EffectiveAdmissionRow;
import dev.flashflow.ordering.persistence.IdempotencyRow;
import java.time.Instant;
import java.util.List;

public record MySqlAdmissionFacts(
        Instant snapshotBoundary,
        AdmissionStockRow stock,
        List<EffectiveAdmissionRow> effectiveOrders,
        List<IdempotencyRow> idempotencyRows) {
}
