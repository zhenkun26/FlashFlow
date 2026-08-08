package dev.flashflow.payment.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PaymentMapper {

    @Insert("""
            INSERT IGNORE INTO payment_callback_event
                (provider_event_id, provider_transaction_id, order_id, request_hash, created_at)
            VALUES (#{eventId}, #{transactionId}, #{orderId}, #{requestHash}, #{now})
            """)
    int tryInsertCallback(@Param("eventId") String eventId,
                          @Param("transactionId") String transactionId,
                          @Param("orderId") String orderId,
                          @Param("requestHash") String requestHash,
                          @Param("now") LocalDateTime now);

    @Select("""
            SELECT provider_event_id, provider_transaction_id, order_id, request_hash, result_code, payment_id
            FROM payment_callback_event WHERE provider_event_id = #{eventId}
            """)
    CallbackEventRow findCallback(String eventId);

    @Update("""
            UPDATE payment_callback_event
            SET result_code = #{resultCode}, payment_id = #{paymentId}, completed_at = #{now}
            WHERE provider_event_id = #{eventId} AND completed_at IS NULL
            """)
    int completeCallback(@Param("eventId") String eventId,
                         @Param("resultCode") String resultCode,
                         @Param("paymentId") String paymentId,
                         @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO payment
                (id, order_id, provider_transaction_id, status, apply_status,
                 amount, currency, paid_at, created_at)
            VALUES (#{id}, #{orderId}, #{transactionId}, 'SUCCEEDED', #{applyStatus},
                    #{amount}, #{currency}, #{paidAt}, #{now})
            """)
    int tryInsertPayment(@Param("id") String id,
                         @Param("orderId") String orderId,
                         @Param("transactionId") String transactionId,
                         @Param("applyStatus") String applyStatus,
                         @Param("amount") BigDecimal amount,
                         @Param("currency") String currency,
                         @Param("paidAt") LocalDateTime paidAt,
                         @Param("now") LocalDateTime now);

    @Select("""
            SELECT id, order_id, provider_transaction_id, status, apply_status, amount, currency
            FROM payment WHERE provider_transaction_id = #{transactionId}
            """)
    PaymentRow findByProviderTransaction(String transactionId);

    @Select("""
            SELECT id, order_id, provider_transaction_id, status, apply_status, amount, currency
            FROM payment WHERE order_id = #{orderId} ORDER BY created_at DESC LIMIT 1
            """)
    PaymentRow findLatestByOrder(String orderId);

    @Insert("""
            INSERT IGNORE INTO compensation_case
                (id, case_type, source_id, order_id, status, details, created_at, updated_at)
            VALUES (#{id}, 'LATE_PAYMENT_REFUND_REQUIRED', #{paymentId}, #{orderId},
                    'OPEN', #{details}, #{now}, #{now})
            """)
    int insertLatePaymentCase(@Param("id") String id,
                              @Param("paymentId") String paymentId,
                              @Param("orderId") String orderId,
                              @Param("details") String details,
                              @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM compensation_case WHERE status = 'OPEN'")
    long countOpenCompensationCases();
}

