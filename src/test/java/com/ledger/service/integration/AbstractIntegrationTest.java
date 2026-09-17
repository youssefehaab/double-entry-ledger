package com.ledger.service.integration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real PostgreSQL instance.
 *
 * <p>Deliberately uses Testcontainers with the real {@code postgres} image
 * rather than H2 (or any in-memory substitute): this schema relies on
 * Postgres-specific behavior - deferred constraint triggers, CHECK
 * constraints, {@code gen_random_uuid()} - that an in-memory DB cannot
 * faithfully emulate.
 *
 * <p>Uses the Testcontainers "singleton container" pattern: the container
 * is started once, manually, in a static initializer, and deliberately
 * never stopped by test code (Testcontainers' Ryuk reaper cleans it up when
 * the JVM exits). This is required, not just an optimization: Spring's test
 * context cache keys a {@code @DynamicPropertySource} context customizer by
 * the declaring method, not by the values it produces, so every subclass of
 * this base class shares one cached Spring ApplicationContext/DataSource.
 * If each subclass instead started (and, via {@code @Container}, tore down)
 * its own container, later test classes would keep the first class's
 * now-stale JDBC URL and fail with connection-refused errors once that
 * first container stopped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("ledger_test")
                    .withUsername("ledger_test")
                    .withPassword("ledger_test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DataSource dataSource;

    /**
     * All integration test classes share one long-lived Postgres container
     * and database (see the singleton-container note above), so table state
     * from one test method/class would otherwise leak into the next. Reset
     * before every test for isolation.
     *
     * <p>Uses TRUNCATE rather than DELETE: the V4 immutability trigger only
     * guards row-level UPDATE/DELETE on entries (as required), not
     * TRUNCATE - so this works here, but it also means TRUNCATE is a
     * standing loophole around append-only-ness in production too. Flagged
     * in the phase report; not fixed here since it is out of the stated
     * scope (UPDATE/DELETE only) and revoking TRUNCATE privilege is a
     * role/privilege-management decision better made alongside real DB role
     * setup in a later phase.
     */
    @BeforeEach
    void resetLedgerTables() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.execute("TRUNCATE TABLE entries, transactions, accounts RESTART IDENTITY CASCADE");
        }
    }
}
