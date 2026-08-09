package dev.flashflow.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class RedisIntegrationTest extends MySqlIntegrationTest {
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.2-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "no", "--maxmemory-policy", "noeviction");

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("flashflow.admission.mode", () -> "REDIS_LUA");
        registry.add("flashflow.admission.identity-secret",
                () -> "integration-test-admission-secret-32-characters");
    }

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void cleanRedis() {
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }
}
