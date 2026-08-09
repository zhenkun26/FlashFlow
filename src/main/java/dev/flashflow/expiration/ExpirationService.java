package dev.flashflow.expiration;

import dev.flashflow.admission.AdmissionAdministrationPort;
import dev.flashflow.admission.AdmissionCommand;
import dev.flashflow.admission.AdmissionGenerationSnapshot;
import dev.flashflow.admission.AdmissionIdentity;
import dev.flashflow.admission.AdmissionLifecycleResult;
import dev.flashflow.admission.AdmissionPort;
import dev.flashflow.inventory.MovementType;
import dev.flashflow.inventory.ReservationStatus;
import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.inventory.persistence.ReservationRow;
import dev.flashflow.ordering.OrderStatus;
import dev.flashflow.ordering.persistence.OrderMapper;
import dev.flashflow.ordering.persistence.OrderRow;
import dev.flashflow.ordering.persistence.IdempotencyRow;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.config.FlashFlowProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ExpirationService {
    private final OrderMapper orderMapper;
    private final InventoryMapper inventoryMapper;
    private final FlashFlowProperties properties;
    private final Clock clock;
    private final FlashFlowMetrics metrics;
    private final ExpirationTransactionHook transactionHook;
    private final AdmissionPort admission;
    private final AdmissionAdministrationPort admissionAdministration;
    private final AdmissionIdentity admissionIdentity;

    public ExpirationService(OrderMapper orderMapper, InventoryMapper inventoryMapper,
                             FlashFlowProperties properties, Clock clock, FlashFlowMetrics metrics,
                             ExpirationTransactionHook transactionHook,
                             AdmissionPort admission,
                             AdmissionAdministrationPort admissionAdministration,
                             AdmissionIdentity admissionIdentity) {
        this.orderMapper = orderMapper;
        this.inventoryMapper = inventoryMapper;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.transactionHook = transactionHook;
        this.admission = admission;
        this.admissionAdministration = admissionAdministration;
        this.admissionIdentity = admissionIdentity;
    }

    @Transactional
    public int expireBatch() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<OrderRow> orders = orderMapper.findExpiredForUpdate(now, properties.expiration().batchSize());
        int expired = 0;
        for (OrderRow order : orders) {
            inventoryMapper.findStockForUpdate(order.activitySkuId());
            ReservationRow reservation = inventoryMapper.findReservationByOrderForUpdate(order.id());
            if (reservation == null || !ReservationStatus.RESERVED.name().equals(reservation.status())) {
                metrics.expirationOutcome("SKIPPED_STATE");
                continue;
            }
            requireOne(orderMapper.transitionStatus(order.id(), OrderStatus.PENDING_PAYMENT.name(),
                    OrderStatus.CLOSED_UNPAID.name(), now), "order expiration transition");
            requireOne(inventoryMapper.transitionReservation(order.id(), ReservationStatus.RELEASED.name(), now),
                    "reservation release");
            requireOne(inventoryMapper.releaseStock(order.activitySkuId(), now), "stock release");
            requireOne(inventoryMapper.insertMovement(UUID.randomUUID().toString(), order.activitySkuId(), order.id(),
                    "release:" + order.id(), MovementType.RELEASE.name(), 1, -1, 0, now), "release movement");
            requireOne(orderMapper.deleteClaim(order.activitySkuId(), order.userId(), order.id()), "claim release");
            registerAdmissionReleaseAfterCommit(order);
            expired++;
            metrics.expirationOutcome("EXPIRED");
        }
        transactionHook.beforeCommit(orders.stream().map(OrderRow::id).toList());
        return expired;
    }

    private void registerAdmissionReleaseAfterCommit(OrderRow order) {
        IdempotencyRow idempotency = orderMapper.findOrderIdempotencyByResourceId(order.id());
        if (idempotency == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            metrics.admissionLifecycle("CLOSURE_RELEASE", "MISSING_IDEMPOTENCY");
            return;
        }
        AdmissionCommand command = new AdmissionCommand(order.activitySkuId(),
                admissionIdentity.admissionId(idempotency.operationName(), idempotency.callerId(),
                        idempotency.idempotencyKey()),
                admissionIdentity.userDigest(order.activitySkuId(), order.userId()),
                clock.instant());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    AdmissionGenerationSnapshot snapshot = admissionAdministration.snapshot(order.activitySkuId());
                    if (snapshot.generation() == null) {
                        metrics.admissionLifecycle("CLOSURE_RELEASE", "NOT_READY");
                        return;
                    }
                    AdmissionLifecycleResult result = admission.release(command, snapshot.generation(), true);
                    metrics.admissionLifecycle("CLOSURE_RELEASE", result.decision().name());
                    if (result.decision() != dev.flashflow.admission.AdmissionLifecycleDecision.RELEASED
                            && result.decision() != dev.flashflow.admission.AdmissionLifecycleDecision.ALREADY_RELEASED) {
                        AdmissionLifecycleResult quarantined = admission.quarantine(command, snapshot.generation());
                        metrics.admissionLifecycle(
                                "QUARANTINE_AFTER_CLOSURE_RELEASE", quarantined.decision().name());
                    }
                } catch (RuntimeException exception) {
                    // The MySQL closure is already committed. Preserve it and surface bounded
                    // reconciliation evidence instead of converting success into an unsafe retry.
                    metrics.admissionLifecycle("CLOSURE_RELEASE", "AMBIGUOUS");
                }
            }
        });
    }

    private static void requireOne(int changed, String operation) {
        if (changed != 1) {
            throw new IllegalStateException(operation + " expected one changed row, got " + changed);
        }
    }
}
