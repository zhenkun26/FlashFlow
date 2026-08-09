package dev.flashflow.ordering.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface OrderMapper {

    @Select("""
            SELECT s.id AS sku_id, a.status AS activity_status, a.starts_at, a.ends_at,
                   s.unit_price, s.currency
            FROM activity_sku_stock s
            JOIN activity a ON a.id = s.activity_id
            WHERE s.id = #{skuId}
            """)
    ActivitySkuView findActivitySku(String skuId);

    @Insert("""
            INSERT IGNORE INTO idempotency_record
                (operation_name, caller_id, idempotency_key, request_hash, status, created_at)
            VALUES (#{operation}, #{callerId}, #{key}, #{requestHash}, 'PROCESSING', #{now})
            """)
    int tryStartIdempotency(@Param("operation") String operation,
                            @Param("callerId") String callerId,
                            @Param("key") String key,
                            @Param("requestHash") String requestHash,
                            @Param("now") LocalDateTime now);

    @Select("""
            SELECT operation_name, caller_id, idempotency_key, request_hash, status, result_code, resource_id
            FROM idempotency_record
            WHERE operation_name = #{operation} AND caller_id = #{callerId} AND idempotency_key = #{key}
            """)
    IdempotencyRow findIdempotency(@Param("operation") String operation,
                                   @Param("callerId") String callerId,
                                   @Param("key") String key);

    @Select("""
            SELECT operation_name, caller_id, idempotency_key, request_hash, status, result_code, resource_id
            FROM idempotency_record
            WHERE operation_name = 'CREATE_ORDER' AND resource_id = #{orderId} AND status = 'COMPLETED'
            LIMIT 1
            """)
    IdempotencyRow findOrderIdempotencyByResourceId(String orderId);

    @Update("""
            UPDATE idempotency_record
            SET status = 'COMPLETED', result_code = #{resultCode}, resource_id = #{resourceId}, completed_at = #{now}
            WHERE operation_name = #{operation} AND caller_id = #{callerId} AND idempotency_key = #{key}
              AND status = 'PROCESSING'
            """)
    int completeIdempotency(@Param("operation") String operation,
                            @Param("callerId") String callerId,
                            @Param("key") String key,
                            @Param("resultCode") String resultCode,
                            @Param("resourceId") String resourceId,
                            @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO orders
                (id, user_id, activity_sku_id, status, unit_price, currency, expires_at, created_at, updated_at)
            VALUES (#{id}, #{userId}, #{skuId}, 'PENDING_PAYMENT', #{unitPrice}, #{currency}, #{expiresAt}, #{now}, #{now})
            """)
    int insertOrder(@Param("id") String id,
                    @Param("userId") String userId,
                    @Param("skuId") String skuId,
                    @Param("unitPrice") BigDecimal unitPrice,
                    @Param("currency") String currency,
                    @Param("expiresAt") LocalDateTime expiresAt,
                    @Param("now") LocalDateTime now);

    @Delete("DELETE FROM orders WHERE id = #{orderId}")
    int deleteOrder(String orderId);

    @Insert("""
            INSERT IGNORE INTO purchase_claim (activity_sku_id, user_id, order_id, created_at)
            VALUES (#{skuId}, #{userId}, #{orderId}, #{now})
            """)
    int tryInsertClaim(@Param("skuId") String skuId,
                       @Param("userId") String userId,
                       @Param("orderId") String orderId,
                       @Param("now") LocalDateTime now);

    @Select("""
            SELECT order_id FROM purchase_claim
            WHERE activity_sku_id = #{skuId} AND user_id = #{userId}
            """)
    String findClaimedOrderId(@Param("skuId") String skuId, @Param("userId") String userId);

    @Select("""
            SELECT order_id FROM purchase_claim
            WHERE activity_sku_id = #{skuId} AND user_id = #{userId}
            FOR UPDATE
            """)
    String findClaimedOrderIdForUpdate(@Param("skuId") String skuId, @Param("userId") String userId);

    @Delete("""
            DELETE FROM purchase_claim
            WHERE activity_sku_id = #{skuId} AND user_id = #{userId} AND order_id = #{orderId}
            """)
    int deleteClaim(@Param("skuId") String skuId,
                    @Param("userId") String userId,
                    @Param("orderId") String orderId);

    @Select("""
            SELECT id, user_id, activity_sku_id, status, unit_price, currency,
                   expires_at, created_at, updated_at, version
            FROM orders WHERE id = #{orderId}
            """)
    OrderRow findById(String orderId);

    @Select("""
            SELECT id, user_id, activity_sku_id, status, unit_price, currency,
                   expires_at, created_at, updated_at, version
            FROM orders WHERE id = #{orderId} FOR UPDATE
            """)
    OrderRow findByIdForUpdate(String orderId);

    @Update("""
            UPDATE orders SET status = #{targetStatus}, updated_at = #{now}, version = version + 1
            WHERE id = #{orderId} AND status = #{sourceStatus}
            """)
    int transitionStatus(@Param("orderId") String orderId,
                         @Param("sourceStatus") String sourceStatus,
                         @Param("targetStatus") String targetStatus,
                         @Param("now") LocalDateTime now);

    @Select("""
            SELECT id, user_id, activity_sku_id, status, unit_price, currency,
                   expires_at, created_at, updated_at, version
            FROM orders
            WHERE status = 'PENDING_PAYMENT' AND expires_at <= #{now}
            ORDER BY expires_at, id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OrderRow> findExpiredForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
