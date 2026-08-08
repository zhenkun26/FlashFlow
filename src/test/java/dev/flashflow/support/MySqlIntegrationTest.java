package dev.flashflow.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest
@ActiveProfiles("test")
public abstract class MySqlIntegrationTest {
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.6")
            .withDatabaseName("flashflow")
            .withUsername("flashflow")
            .withPassword("flashflow")
            .withCommand("--default-time-zone=+00:00", "--transaction-isolation=REPEATABLE-READ");

    static {
        // Spring caches the application context across subclasses. Keep its database alive
        // for the same JVM lifetime instead of stopping it after the first test class.
        MYSQL.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    protected final DatabaseFixture fixture() {
        return new DatabaseFixture(jdbc);
    }

    @BeforeEach
    void cleanDatabase() {
        fixture().reset();
    }
}
