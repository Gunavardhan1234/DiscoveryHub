
package com.smarsh.discoveryhub.testsupport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * Base class for any test that needs a real PostgreSQL 17 instance with Flyway
 * migrations applied. Subclasses call {@link #jdbc()} for a {@link JdbcTemplate}
 * or {@link #dataSource()} for raw access.
 *
 * <p>Skips the whole class when Docker is not running.</p>
 *
 * <p>Subclasses put their Flyway migrations on the test classpath at
 * {@code classpath:db/migration} (the default Spring Boot location), or override
 * {@link #migrationLocations()}.</p>
 */
public abstract class PostgresTestBase {

    protected static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startPostgres() {
        Assumptions.assumeTrue(DockerChecks.dockerAvailable(),
                "Docker is unavailable; skipping Postgres integration test");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations(migrationLocations())
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    protected static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    protected static DataSource dataSource() {
        return dataSource;
    }

    protected static String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    protected static String dbUsername() {
        return postgres.getUsername();
    }

    protected static String dbPassword() {
        return postgres.getPassword();
    }

    /** Override to point at a non-default migration location. */
    protected static String[] migrationLocations() {
        return new String[]{"classpath:db/migration"};
    }
}
