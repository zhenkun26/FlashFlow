package dev.flashflow.messaging.persistence;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CommandMapper {
    @Insert("""
            INSERT IGNORE INTO order_command_ledger
                (command_id, operation_name, caller_id, idempotency_key, activity_sku_id,
                 payload_fingerprint, schema_version, status, created_at, updated_at)
            VALUES (#{commandId}, 'CREATE_ORDER', #{callerId}, #{idempotencyKey}, #{skuId},
                    #{fingerprint}, #{schemaVersion}, 'PREPARED', #{now}, #{now})
            """)
    int tryInsert(@Param("commandId") String commandId, @Param("callerId") String callerId,
                  @Param("idempotencyKey") String idempotencyKey, @Param("skuId") String skuId,
                  @Param("fingerprint") String fingerprint, @Param("schemaVersion") int schemaVersion,
                  @Param("now") LocalDateTime now);

    @Select("SELECT * FROM order_command_ledger WHERE command_id = #{commandId}")
    CommandRow findById(String commandId);

    @Update("""
            UPDATE order_command_ledger SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                updated_at = #{now}
            WHERE command_id = #{commandId}
              AND status IN ('PREPARED', 'ACCEPTED', 'PROCESSING', 'RETRYABLE', 'UNRESOLVED')
            """)
    int claim(@Param("commandId") String commandId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE order_command_ledger SET status = #{status}, result_code = #{resultCode},
                order_id = #{orderId}, updated_at = #{now}, completed_at = #{now}
            WHERE command_id = #{commandId} AND status = 'PROCESSING'
            """)
    int finish(@Param("commandId") String commandId, @Param("status") String status,
               @Param("resultCode") String resultCode, @Param("orderId") String orderId,
               @Param("now") LocalDateTime now);

    @Update("""
            UPDATE order_command_ledger SET status = #{status}, updated_at = #{now}
            WHERE command_id = #{commandId} AND status IN ('PREPARED', 'ACCEPTED', 'PROCESSING', 'RETRYABLE')
            """)
    int markNonTerminal(@Param("commandId") String commandId, @Param("status") String status,
                        @Param("now") LocalDateTime now);
}
