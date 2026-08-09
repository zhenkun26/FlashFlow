package dev.flashflow.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.flashflow.shared.config.FlashFlowProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class RedisLuaAdmissionFailureTest {
    @Test
    void classifiesUnavailableTimeoutAndMalformedRepliesWithoutThrowing() {
        assertThat(adapterThrowing(new RedisConnectionFailureException("offline"))
                .acquire(command()).decision()).isEqualTo(AdmissionDecision.UNAVAILABLE);
        assertThat(adapterThrowing(new QueryTimeoutException("lost reply"))
                .acquire(command()).decision()).isEqualTo(AdmissionDecision.AMBIGUOUS);

        StringRedisTemplate malformedRedis = mock(StringRedisTemplate.class);
        when(malformedRedis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of("UNKNOWN_DECISION"));
        assertThat(new RedisLuaAdmissionAdapter(malformedRedis, properties())
                .acquire(command()).decision()).isEqualTo(AdmissionDecision.MALFORMED_REPLY);
    }

    private static RedisLuaAdmissionAdapter adapterThrowing(RuntimeException failure) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(failure);
        return new RedisLuaAdmissionAdapter(redis, properties());
    }

    private static AdmissionCommand command() {
        return new AdmissionCommand("s1", "a1", "u1", Instant.now().plusSeconds(30));
    }

    private static FlashFlowProperties properties() {
        return new FlashFlowProperties(
                new FlashFlowProperties.Inventory(
                        FlashFlowProperties.Strategy.CONDITIONAL_ATOMIC, 0),
                new FlashFlowProperties.Ordering(
                        0, 0, FlashFlowProperties.TransactionSequence.STOCK_FIRST),
                new FlashFlowProperties.Expiration(Duration.ofMinutes(15), 100, false),
                new FlashFlowProperties.Admission(
                        FlashFlowProperties.AdmissionMode.REDIS_LUA, Duration.ofSeconds(30),
                        "v2", "integration-test-admission-secret-32-characters"));
    }
}
