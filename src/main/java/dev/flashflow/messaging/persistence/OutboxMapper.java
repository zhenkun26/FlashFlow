package dev.flashflow.messaging.persistence;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxMapper {
    @Insert("""
            INSERT IGNORE INTO order_command_outbox
                (outbox_id, command_id, schema_version, envelope_payload, envelope_fingerprint,
                 topic_name, tag_name, status, attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (#{outboxId}, #{commandId}, #{schemaVersion}, #{payload}, #{fingerprint},
                    #{topic}, #{tag}, 'READY', 0, #{now}, #{now}, #{now})
            """)
    int tryInsert(@Param("outboxId") String outboxId, @Param("commandId") String commandId,
                  @Param("schemaVersion") int schemaVersion, @Param("payload") String payload,
                  @Param("fingerprint") String fingerprint, @Param("topic") String topic,
                  @Param("tag") String tag, @Param("now") LocalDateTime now);

    @Select("SELECT * FROM order_command_outbox WHERE outbox_id = #{outboxId}")
    OutboxRow findById(String outboxId);

    @Select("SELECT * FROM order_command_outbox WHERE command_id = #{commandId}")
    OutboxRow findByCommandId(String commandId);

    @Select("""
            SELECT * FROM order_command_outbox
            WHERE ((status IN ('READY', 'RETRYABLE') AND next_attempt_at <= #{now})
                    OR (status = 'CLAIMED' AND lease_until <= #{now}))
            ORDER BY next_attempt_at, created_at, outbox_id
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<OutboxRow> findEligibleForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE order_command_outbox
            SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                lease_token = #{leaseToken}, lease_owner = #{leaseOwner}, lease_until = #{leaseUntil},
                result_code = NULL, updated_at = #{now}
            WHERE outbox_id = #{outboxId}
              AND ((status IN ('READY', 'RETRYABLE') AND next_attempt_at <= #{now})
                    OR (status = 'CLAIMED' AND lease_until <= #{now}))
            """)
    int claim(@Param("outboxId") String outboxId, @Param("leaseToken") String leaseToken,
              @Param("leaseOwner") String leaseOwner, @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE order_command_outbox
            SET status = 'ACKNOWLEDGED', lease_token = NULL, lease_owner = NULL, lease_until = NULL,
                result_code = #{resultCode}, acknowledged_at = #{now}, updated_at = #{now}
            WHERE outbox_id = #{outboxId} AND status = 'CLAIMED' AND lease_token = #{leaseToken}
            """)
    int acknowledge(@Param("outboxId") String outboxId, @Param("leaseToken") String leaseToken,
                    @Param("resultCode") String resultCode, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE order_command_outbox
            SET status = 'RETRYABLE', next_attempt_at = #{nextAttemptAt},
                lease_token = NULL, lease_owner = NULL, lease_until = NULL,
                result_code = #{resultCode}, updated_at = #{now}
            WHERE outbox_id = #{outboxId} AND status = 'CLAIMED' AND lease_token = #{leaseToken}
            """)
    int retry(@Param("outboxId") String outboxId, @Param("leaseToken") String leaseToken,
              @Param("resultCode") String resultCode, @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE order_command_outbox
            SET status = #{status}, lease_token = NULL, lease_owner = NULL, lease_until = NULL,
                result_code = #{resultCode}, updated_at = #{now}
            WHERE outbox_id = #{outboxId} AND status = 'CLAIMED' AND lease_token = #{leaseToken}
              AND #{status} IN ('INVALID', 'EXHAUSTED')
            """)
    int stop(@Param("outboxId") String outboxId, @Param("leaseToken") String leaseToken,
             @Param("status") String status, @Param("resultCode") String resultCode,
             @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM order_command_outbox
            WHERE status IN ('READY', 'RETRYABLE') AND next_attempt_at <= #{now}
            """)
    long readyCount(LocalDateTime now);

    @Select("""
            SELECT MIN(next_attempt_at) FROM order_command_outbox
            WHERE status IN ('READY', 'RETRYABLE') AND next_attempt_at <= #{now}
            """)
    LocalDateTime oldestReadyAt(LocalDateTime now);

    @Select("""
            SELECT o.* FROM order_command_outbox o
            JOIN order_command_ledger c ON c.command_id = o.command_id
            WHERE o.status = 'ACKNOWLEDGED' AND o.acknowledged_at < #{cutoff}
              AND c.status IN ('COMPLETED', 'REJECTED')
            ORDER BY o.acknowledged_at, o.outbox_id
            LIMIT #{limit}
            """)
    List<OutboxRow> findCleanupEligible(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
