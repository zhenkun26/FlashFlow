package dev.flashflow.ordering;

import dev.flashflow.inventory.persistence.InventoryMapper;
import dev.flashflow.inventory.persistence.ReservationRow;
import dev.flashflow.ordering.persistence.OrderMapper;
import dev.flashflow.ordering.persistence.OrderRow;
import dev.flashflow.payment.persistence.PaymentMapper;
import dev.flashflow.payment.persistence.PaymentRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueryService {
    private final OrderMapper orderMapper;
    private final InventoryMapper inventoryMapper;
    private final PaymentMapper paymentMapper;

    public OrderQueryService(OrderMapper orderMapper, InventoryMapper inventoryMapper, PaymentMapper paymentMapper) {
        this.orderMapper = orderMapper;
        this.inventoryMapper = inventoryMapper;
        this.paymentMapper = paymentMapper;
    }

    @Transactional(readOnly = true)
    public OrderSummary find(String orderId) {
        OrderRow order = orderMapper.findById(orderId);
        if (order == null) {
            return null;
        }
        ReservationRow reservation = inventoryMapper.findReservationByOrder(orderId);
        PaymentRow payment = paymentMapper.findLatestByOrder(orderId);
        return new OrderSummary(
                order.id(), order.userId(), order.activitySkuId(), OrderStatus.valueOf(order.status()),
                order.unitPrice(), order.currency(), order.expiresAt(),
                reservation == null ? null : reservation.status(),
                payment == null ? null : payment.status(),
                payment == null ? null : payment.applyStatus());
    }
}

