package dev.flashflow.inventory.persistence;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface InventoryMapper {

    @Select("""
            SELECT id, initial_stock, available_stock, reserved_stock, sold_stock, version
            FROM activity_sku_stock WHERE id = #{skuId}
            """)
    StockRow findStock(String skuId);

    @Select("""
            SELECT id, initial_stock, available_stock, reserved_stock, sold_stock, version
            FROM activity_sku_stock WHERE id = #{skuId} FOR UPDATE
            """)
    StockRow findStockForUpdate(String skuId);

    @Update("""
            UPDATE activity_sku_stock
            SET available_stock = available_stock - 1,
                reserved_stock = reserved_stock + 1,
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{skuId} AND available_stock >= 1
            """)
    int reserveConditional(@Param("skuId") String skuId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE activity_sku_stock
            SET available_stock = available_stock - 1,
                reserved_stock = reserved_stock + 1,
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{skuId} AND available_stock >= 1 AND version = #{version}
            """)
    int reserveOptimistic(@Param("skuId") String skuId,
                          @Param("version") long version,
                          @Param("now") LocalDateTime now);

    @Update("""
            UPDATE activity_sku_stock
            SET available_stock = #{available}, reserved_stock = #{reserved},
                version = version + 1, updated_at = #{now}
            WHERE id = #{skuId}
            """)
    int overwriteUnsafe(@Param("skuId") String skuId,
                        @Param("available") int available,
                        @Param("reserved") int reserved,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE activity_sku_stock
            SET reserved_stock = reserved_stock - 1,
                sold_stock = sold_stock + 1,
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{skuId} AND reserved_stock >= 1
            """)
    int confirmStock(@Param("skuId") String skuId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE activity_sku_stock
            SET reserved_stock = reserved_stock - 1,
                available_stock = available_stock + 1,
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{skuId} AND reserved_stock >= 1
            """)
    int releaseStock(@Param("skuId") String skuId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO inventory_reservation
                (id, order_id, activity_sku_id, quantity, status, expires_at, created_at, updated_at)
            VALUES (#{id}, #{orderId}, #{skuId}, 1, 'RESERVED', #{expiresAt}, #{now}, #{now})
            """)
    int insertReservation(@Param("id") String id,
                          @Param("orderId") String orderId,
                          @Param("skuId") String skuId,
                          @Param("expiresAt") LocalDateTime expiresAt,
                          @Param("now") LocalDateTime now);

    @Select("""
            SELECT id, order_id, activity_sku_id, quantity, status, expires_at
            FROM inventory_reservation WHERE order_id = #{orderId}
            """)
    ReservationRow findReservationByOrder(String orderId);

    @Select("""
            SELECT id, order_id, activity_sku_id, quantity, status, expires_at
            FROM inventory_reservation WHERE order_id = #{orderId} FOR UPDATE
            """)
    ReservationRow findReservationByOrderForUpdate(String orderId);

    @Update("""
            UPDATE inventory_reservation SET status = #{targetStatus}, updated_at = #{now}
            WHERE order_id = #{orderId} AND status = 'RESERVED'
            """)
    int transitionReservation(@Param("orderId") String orderId,
                              @Param("targetStatus") String targetStatus,
                              @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO inventory_movement
                (id, activity_sku_id, order_id, operation_id, movement_type,
                 available_delta, reserved_delta, sold_delta, created_at)
            VALUES (#{id}, #{skuId}, #{orderId}, #{operationId}, #{movementType},
                    #{availableDelta}, #{reservedDelta}, #{soldDelta}, #{now})
            """)
    int insertMovement(@Param("id") String id,
                       @Param("skuId") String skuId,
                       @Param("orderId") String orderId,
                       @Param("operationId") String operationId,
                       @Param("movementType") String movementType,
                       @Param("availableDelta") int availableDelta,
                       @Param("reservedDelta") int reservedDelta,
                       @Param("soldDelta") int soldDelta,
                       @Param("now") LocalDateTime now);
}

