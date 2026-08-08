package dev.flashflow.payment;

import dev.flashflow.inventory.MovementType;
import dev.flashflow.inventory.ReservationStatus;
import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.inventory.persistence.ReservationRow;
import dev.flashflow.ordering.OrderStatus;
import dev.flashflow.ordering.persistence.OrderMapper;
import dev.flashflow.ordering.persistence.OrderRow;
import dev.flashflow.payment.persistence.CallbackEventRow;
import dev.flashflow.payment.persistence.PaymentMapper;
import dev.flashflow.payment.persistence.PaymentRow;
import dev.flashflow.shared.FlashFlowMetrics;
import dev.flashflow.shared.RequestHash;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentApplicationService {
    private final OrderMapper orderMapper;
    private final InventoryMapper inventoryMapper;
    private final PaymentMapper paymentMapper;
    private final Clock clock;
    private final FlashFlowMetrics metrics;

    public PaymentApplicationService(OrderMapper orderMapper, InventoryMapper inventoryMapper,
                                     PaymentMapper paymentMapper, Clock clock, FlashFlowMetrics metrics) {
        this.orderMapper = orderMapper;
        this.inventoryMapper = inventoryMapper;
        this.paymentMapper = paymentMapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public PaymentResult apply(PaymentCallbackCommand command) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OrderRow order = orderMapper.findByIdForUpdate(command.orderId());
        if (order == null) {
            return measured(new PaymentResult(PaymentResult.Code.ORDER_NOT_FOUND, null, null, "Order not found"));
        }

        String requestHash = RequestHash.payment(command.providerEventId(), command.providerTransactionId(),
                command.orderId(), command.amount().toPlainString(), command.currency());
        if (paymentMapper.tryInsertCallback(command.providerEventId(), command.providerTransactionId(),
                command.orderId(), requestHash, now) == 0) {
            CallbackEventRow existing = paymentMapper.findCallback(command.providerEventId());
            if (existing == null || !existing.requestHash().equals(requestHash)) {
                return measured(new PaymentResult(PaymentResult.Code.CALLBACK_CONFLICT, null, null,
                        "Provider event was reused with different content"));
            }
            PaymentRow payment = existing.paymentId() == null
                    ? null
                    : paymentMapper.findByProviderTransaction(existing.providerTransactionId());
            PaymentApplyStatus applyStatus = payment == null ? null : PaymentApplyStatus.valueOf(payment.applyStatus());
            return measured(new PaymentResult(PaymentResult.Code.DUPLICATE, existing.paymentId(), applyStatus,
                    "Callback already processed"));
        }

        PaymentRow sameTransaction = paymentMapper.findByProviderTransaction(command.providerTransactionId());
        if (sameTransaction != null) {
            PaymentResult.Code code = sameTransaction.orderId().equals(order.id())
                    ? PaymentResult.Code.DUPLICATE
                    : PaymentResult.Code.PROVIDER_TRANSACTION_CONFLICT;
            paymentMapper.completeCallback(command.providerEventId(), code.name(), sameTransaction.id(), now);
            return measured(new PaymentResult(code, sameTransaction.id(),
                    PaymentApplyStatus.valueOf(sameTransaction.applyStatus()), "Provider transaction already exists"));
        }

        if (order.unitPrice().compareTo(command.amount()) != 0 || !order.currency().equals(command.currency())) {
            paymentMapper.completeCallback(command.providerEventId(), PaymentResult.Code.INVALID_AMOUNT.name(), null, now);
            return measured(new PaymentResult(PaymentResult.Code.INVALID_AMOUNT, null, null,
                    "Payment amount or currency does not match the order"));
        }

        OrderStatus status = OrderStatus.valueOf(order.status());
        if (status == OrderStatus.PAID) {
            paymentMapper.completeCallback(command.providerEventId(), PaymentResult.Code.ORDER_ALREADY_PAID.name(), null, now);
            return measured(new PaymentResult(PaymentResult.Code.ORDER_ALREADY_PAID, null, null,
                    "Order is already paid"));
        }

        inventoryMapper.findStockForUpdate(order.activitySkuId());
        ReservationRow reservation = inventoryMapper.findReservationByOrderForUpdate(order.id());
        if (reservation == null) {
            throw new IllegalStateException("Order has no inventory reservation");
        }

        if (status == OrderStatus.PENDING_PAYMENT) {
            String paymentId = UUID.randomUUID().toString();
            insertPayment(paymentId, order, command, PaymentApplyStatus.APPLIED, now);
            requireOne(orderMapper.transitionStatus(order.id(), OrderStatus.PENDING_PAYMENT.name(),
                    OrderStatus.PAID.name(), now), "order payment transition");
            requireOne(inventoryMapper.transitionReservation(order.id(), ReservationStatus.CONFIRMED.name(), now),
                    "reservation confirmation");
            requireOne(inventoryMapper.confirmStock(order.activitySkuId(), now), "stock confirmation");
            requireOne(inventoryMapper.insertMovement(UUID.randomUUID().toString(), order.activitySkuId(), order.id(),
                    "confirm:" + paymentId, MovementType.CONFIRM.name(), 0, -1, 1, now), "confirm movement");
            paymentMapper.completeCallback(command.providerEventId(), PaymentResult.Code.APPLIED.name(), paymentId, now);
            return measured(new PaymentResult(PaymentResult.Code.APPLIED, paymentId,
                    PaymentApplyStatus.APPLIED, "Payment applied"));
        }

        String paymentId = UUID.randomUUID().toString();
        insertPayment(paymentId, order, command, PaymentApplyStatus.REFUND_REQUIRED, now);
        paymentMapper.insertLatePaymentCase(UUID.randomUUID().toString(), paymentId, order.id(),
                "Successful payment arrived after unpaid closure", now);
        paymentMapper.completeCallback(command.providerEventId(), PaymentResult.Code.REFUND_REQUIRED.name(), paymentId, now);
        return measured(new PaymentResult(PaymentResult.Code.REFUND_REQUIRED, paymentId,
                PaymentApplyStatus.REFUND_REQUIRED, "Late payment requires refund"));
    }

    private void insertPayment(String paymentId, OrderRow order, PaymentCallbackCommand command,
                               PaymentApplyStatus applyStatus, LocalDateTime now) {
        requireOne(paymentMapper.tryInsertPayment(paymentId, order.id(), command.providerTransactionId(),
                applyStatus.name(), command.amount(), command.currency(), command.paidAt(), now), "payment insert");
    }

    private PaymentResult measured(PaymentResult result) {
        metrics.paymentOutcome(result.code().name());
        return result;
    }

    private static void requireOne(int changed, String operation) {
        if (changed != 1) {
            throw new IllegalStateException(operation + " expected one changed row, got " + changed);
        }
    }
}

