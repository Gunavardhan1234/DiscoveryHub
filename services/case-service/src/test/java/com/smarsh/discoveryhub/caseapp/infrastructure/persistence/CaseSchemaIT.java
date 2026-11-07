package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Testcontainers: spins up a real, disposable Postgres in Docker rather than mocking JDBC or using
// an in-memory H2 substitute — H2 doesn't support Postgres-specific types (named enums, jsonb)
// this schema actually uses, so only a real Postgres instance can catch a type-mismatch bug here.
class CaseSchemaIT {
    private static PostgreSQLContainer<?> postgres;
    private static CaseSchemaRepository repository;

    @BeforeAll
    static void startDatabase() {
        // Graceful skip, not a failure: if Docker isn't available in this environment (e.g. a
        // constrained CI runner), this test is skipped rather than failing the whole build —
        // Assumptions.assumeTrue is JUnit 5's mechanism for "this test doesn't apply right now".
        Assumptions.assumeTrue(dockerAvailable(), "Docker is unavailable; skipping CaseSchemaIT");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        repository = new CaseSchemaRepository(new JdbcTemplate(dataSource));
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void migratesAndRoundTripsEnumsAndJsonb() {
        UUID caseId = UUID.randomUUID();
        repository.saveFixture(caseId, "{\"q\":\"invoice\"}");

        var result = repository.findFixture(caseId);

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.query()).isEqualTo("invoice");
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
