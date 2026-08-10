package dev.flashflow.admission.persistence;

import dev.flashflow.ordering.persistence.IdempotencyRow;
import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface AdmissionReconciliationMapper {
    @Select("""
            SELECT id AS sku_id, available_stock, reserved_stock, sold_stock
            FROM activity_sku_stock WHERE id = #{skuId}
            """)
    AdmissionStockRow findStock(String skuId);

    @Select("""
            SELECT o.id AS order_id, o.user_id, o.status, i.caller_id, i.idempotency_key
            FROM orders o
            JOIN purchase_claim c ON c.order_id = o.id
            LEFT JOIN idempotency_record i
              ON i.operation_name = 'CREATE_ORDER' AND i.resource_id = o.id AND i.status = 'COMPLETED'
            WHERE o.activity_sku_id = #{skuId} AND o.status IN ('PENDING_PAYMENT', 'PAID')
            ORDER BY o.id
            """)
    List<EffectiveAdmissionRow> findEffectiveOrders(String skuId);

    @Select("""
            SELECT operation_name, caller_id, idempotency_key, request_hash, status, result_code, resource_id
            FROM idempotency_record WHERE operation_name = 'CREATE_ORDER'
            """)
    List<IdempotencyRow> findAllOrderIdempotency();

    @Select("""
            SELECT command_id, status, transport_cause, updated_at, dead_lettered_at
            FROM order_command_ledger
            WHERE activity_sku_id = #{skuId}
            """)
    List<CommandAdmissionRow> findCommands(String skuId);
}
