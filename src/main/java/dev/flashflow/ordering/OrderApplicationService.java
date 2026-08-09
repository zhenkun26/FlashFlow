package dev.flashflow.ordering;

import dev.flashflow.inventory.InventoryReservationStrategy;
import dev.flashflow.inventory.InventoryStrategyRegistry;
import dev.flashflow.inventory.MovementType;
import dev.flashflow.inventory.ReservationAttempt;
import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.ordering.persistence.ActivitySkuView;
import dev.flashflow.ordering.persistence.IdempotencyRow;
import dev.flashflow.ordering.persistence.OrderMapper;
import dev.flashflow.ordering.persistence.OrderRow;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.OrderAttemptOutcome;
import dev.flashflow.shared.RequestHash;
import dev.flashflow.shared.config.FlashFlowProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class OrderApplicationService {
    static final String IDEMPOTENCY_OPERATION = "CREATE_ORDER";

    private final OrderMapper orderMapper;
    private final InventoryMapper inventoryMapper;
    private final InventoryStrategyRegistry strategyRegistry;
    private final FlashFlowProperties properties;
    private final Clock clock;
    private final FlashFlowMetrics metrics;
    private final TransactionTemplate transactions;
    private final OrderingTransactionHook transactionHook;

    public OrderApplicationService(
            OrderMapper orderMapper,
            InventoryMapper inventoryMapper,
            InventoryStrategyRegistry strategyRegistry,
            FlashFlowProperties properties,
            Clock clock,
            FlashFlowMetrics metrics,
            PlatformTransactionManager transactionManager,
            OrderingTransactionHook transactionHook) {
        this.orderMapper = orderMapper;
        this.inventoryMapper = inventoryMapper;
        this.strategyRegistry = strategyRegistry;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactionHook = transactionHook;
    }

    public OrderResult place(PlaceOrderCommand command) {
        Objects.requireNonNull(command, "command");
        InventoryReservationStrategy strategy = strategyRegistry.require(properties.inventory().strategy());
        String strategyName = strategy.kind().name();
        int optimisticConflicts = 0;
        int transactionRetries = 0;
        int claimRaceRetries = 0;

        while (true) {
            metrics.orderAttempt(strategyName);
            try {
                OrderResult result = metrics.timeOrder(() -> transactions.execute(status -> placeOnce(command, strategy)));
                if (result == null) {
                    throw new IllegalStateException("Ordering transaction returned no result");
                }
                metrics.orderOutcome(result.code().name());
                return result;
            } catch (OptimisticContentionException conflict) {
                metrics.strategyConflict(strategyName);
                metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.OPTIMISTIC_CONFLICT);
                if (optimisticConflicts++ >= properties.inventory().optimisticMaxRetries()) {
                    OrderResult result = OrderResult.rejected(
                            OrderResultCode.RETRYABLE_CONTENTION,
                            "Inventory changed concurrently; retry with the same idempotency key");
                    metrics.orderOutcome(result.code().name());
                    metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.RETRY_EXHAUSTED);
                    return result;
                }
                Thread.onSpinWait();
            } catch (TransientDataAccessException conflict) {
                // InnoDB resolves deadlocks by rolling back one complete transaction. Replay the
                // entire command with the same idempotency key; never retry only the failed SQL.
                metrics.strategyConflict(strategyName);
                metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.TRANSIENT_DATABASE_RETRY);
                if (transactionRetries++ >= properties.ordering().transactionMaxRetries()) {
                    OrderResult result = OrderResult.rejected(
                            OrderResultCode.RETRYABLE_CONTENTION,
                            "Database contention retry budget exhausted; retry with the same idempotency key");
                    metrics.orderOutcome(result.code().name());
                    metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.RETRY_EXHAUSTED);
                    return result;
                }
                Thread.onSpinWait();
            } catch (ClaimRaceException conflict) {
                metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.CLAIM_RACE_ROLLBACK);
                if (claimRaceRetries++ >= properties.ordering().claimRaceMaxRetries()) {
                    OrderResult result = OrderResult.rejected(
                            OrderResultCode.RETRYABLE_CONTENTION,
                            "Effective-order race retry budget exhausted; retry with the same idempotency key");
                    metrics.orderOutcome(result.code().name());
                    metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.CLAIM_RACE_EXHAUSTED);
                    return result;
                }
                metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.CLAIM_RACE_REPLAY);
                Thread.onSpinWait();
            } catch (RuntimeException exception) {
                if (isConnectionAcquisitionFailure(exception)) {
                    metrics.orderAttemptOutcome(strategyName,
                            OrderAttemptOutcome.CONNECTION_POOL_ACQUISITION_FAILURE);
                    metrics.orderOutcome(OrderAttemptOutcome.CONNECTION_POOL_ACQUISITION_FAILURE.name());
                } else {
                    metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.UNEXPECTED_FAILURE);
                    metrics.orderOutcome(OrderAttemptOutcome.UNEXPECTED_FAILURE.name());
                }
                throw exception;
            } catch (Exception exception) {
                metrics.orderAttemptOutcome(strategyName, OrderAttemptOutcome.UNEXPECTED_FAILURE);
                metrics.orderOutcome(OrderAttemptOutcome.UNEXPECTED_FAILURE.name());
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Ordering transaction failed", exception);
            }
        }
    }

    static boolean isConnectionAcquisitionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    private OrderResult placeOnce(PlaceOrderCommand command, InventoryReservationStrategy strategy) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String requestHash = RequestHash.order(command.userId(), command.activitySkuId());

        int started = orderMapper.tryStartIdempotency(
                IDEMPOTENCY_OPERATION, command.userId(), command.idempotencyKey(), requestHash, now);
        if (started == 0) {
            metrics.idempotencyHit();
            return replay(command, requestHash);
        }

        ActivitySkuView sku = orderMapper.findActivitySku(command.activitySkuId());
        if (sku == null || !sku.acceptsOrdersAt(now)) {
            complete(command, OrderResultCode.ACTIVITY_NOT_ACTIVE, null, now);
            return OrderResult.rejected(OrderResultCode.ACTIVITY_NOT_ACTIVE, "Activity is not accepting orders");
        }

        String existingOrderId = orderMapper.findClaimedOrderId(command.activitySkuId(), command.userId());
        if (existingOrderId != null) {
            complete(command, OrderResultCode.EXISTING_EFFECTIVE_ORDER, existingOrderId, now);
            return fromExisting(OrderResultCode.EXISTING_EFFECTIVE_ORDER, existingOrderId,
                    "User already has an effective order");
        }
        transactionHook.afterClaimPrecheck(command);

        if (properties.ordering().transactionSequence()
                == FlashFlowProperties.TransactionSequence.CHILD_FIRST_LEGACY) {
            return placeChildFirstLegacy(command, strategy, sku, now);
        }

        ReservationAttempt reservationAttempt = strategy.reserve(command.activitySkuId(), now);
        if (reservationAttempt == ReservationAttempt.CONFLICT) {
            throw new OptimisticContentionException();
        }
        if (reservationAttempt == ReservationAttempt.SOLD_OUT) {
            existingOrderId = orderMapper.findClaimedOrderIdForUpdate(
                    command.activitySkuId(), command.userId());
            if (existingOrderId != null) {
                complete(command, OrderResultCode.EXISTING_EFFECTIVE_ORDER, existingOrderId, now);
                return fromExisting(OrderResultCode.EXISTING_EFFECTIVE_ORDER, existingOrderId,
                        "User already has an effective order");
            }
            complete(command, OrderResultCode.SOLD_OUT, null, now);
            return OrderResult.rejected(OrderResultCode.SOLD_OUT, "Inventory is sold out");
        }

        String orderId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = now.plus(properties.expiration().orderTtl());
        orderMapper.insertOrder(orderId, command.userId(), command.activitySkuId(),
                sku.unitPrice(), sku.currency(), expiresAt, now);

        if (orderMapper.tryInsertClaim(command.activitySkuId(), command.userId(), orderId, now) == 0) {
            throw new ClaimRaceException();
        }

        return finishCreatedOrder(command, orderId, expiresAt, now);
    }

    /** Lab-only reproduction path retained solely for controlled before/after characterization. */
    private OrderResult placeChildFirstLegacy(
            PlaceOrderCommand command,
            InventoryReservationStrategy strategy,
            ActivitySkuView sku,
            LocalDateTime now) {
        String orderId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = now.plus(properties.expiration().orderTtl());
        orderMapper.insertOrder(orderId, command.userId(), command.activitySkuId(),
                sku.unitPrice(), sku.currency(), expiresAt, now);
        if (orderMapper.tryInsertClaim(command.activitySkuId(), command.userId(), orderId, now) == 0) {
            throw new ClaimRaceException();
        }

        ReservationAttempt reservationAttempt = strategy.reserve(command.activitySkuId(), now);
        if (reservationAttempt == ReservationAttempt.CONFLICT) {
            throw new OptimisticContentionException();
        }
        if (reservationAttempt == ReservationAttempt.SOLD_OUT) {
            orderMapper.deleteClaim(command.activitySkuId(), command.userId(), orderId);
            orderMapper.deleteOrder(orderId);
            complete(command, OrderResultCode.SOLD_OUT, null, now);
            return OrderResult.rejected(OrderResultCode.SOLD_OUT, "Inventory is sold out");
        }

        return finishCreatedOrder(command, orderId, expiresAt, now);
    }

    private OrderResult finishCreatedOrder(
            PlaceOrderCommand command, String orderId, LocalDateTime expiresAt, LocalDateTime now) {

        inventoryMapper.insertReservation(
                UUID.randomUUID().toString(), orderId, command.activitySkuId(), expiresAt, now);
        int movement = inventoryMapper.insertMovement(
                UUID.randomUUID().toString(), command.activitySkuId(), orderId,
                "reserve:" + orderId, MovementType.RESERVE.name(), -1, 1, 0, now);
        if (movement != 1) {
            throw new IllegalStateException("Reserve movement must be unique and newly inserted");
        }
        complete(command, OrderResultCode.CREATED, orderId, now);
        return new OrderResult(OrderResultCode.CREATED, orderId, OrderStatus.PENDING_PAYMENT,
                expiresAt, "Order created");
    }

    private OrderResult replay(PlaceOrderCommand command, String requestHash) {
        IdempotencyRow record = orderMapper.findIdempotency(
                IDEMPOTENCY_OPERATION, command.userId(), command.idempotencyKey());
        if (record == null || !record.requestHash().equals(requestHash)) {
            return OrderResult.rejected(OrderResultCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency key was used with a different payload");
        }
        if (!"COMPLETED".equals(record.status())) {
            return OrderResult.rejected(OrderResultCode.RETRYABLE_CONTENTION,
                    "Original request is still processing");
        }
        OrderResultCode code = OrderResultCode.valueOf(record.resultCode());
        if (record.resourceId() == null) {
            return OrderResult.rejected(code, "Replayed committed result");
        }
        return fromExisting(code, record.resourceId(), "Replayed committed result");
    }

    private OrderResult fromExisting(OrderResultCode code, String orderId, String message) {
        if (orderId == null) {
            return OrderResult.rejected(code, message);
        }
        OrderRow order = orderMapper.findById(orderId);
        if (order == null) {
            return OrderResult.rejected(code, message);
        }
        return new OrderResult(code, order.id(), OrderStatus.valueOf(order.status()), order.expiresAt(), message);
    }

    private void complete(PlaceOrderCommand command, OrderResultCode code, String resourceId, LocalDateTime now) {
        if (orderMapper.completeIdempotency(IDEMPOTENCY_OPERATION, command.userId(), command.idempotencyKey(),
                code.name(), resourceId, now) != 1) {
            throw new IllegalStateException("Idempotency result was not completed");
        }
    }

    public OrderRow findOrder(String orderId) {
        return orderMapper.findById(orderId);
    }
}
