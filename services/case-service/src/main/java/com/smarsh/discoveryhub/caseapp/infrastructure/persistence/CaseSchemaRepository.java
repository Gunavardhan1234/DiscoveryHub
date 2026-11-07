package com.smarsh.discoveryhub.caseapp.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Types;
import java.util.UUID;

/**
 * Exercises the Postgres-specific types case-service's own schema actually
 * uses (enum casts on {@code cases}, {@code jsonb} on {@code saved_searches})
 * end-to-end through Flyway + JDBC. Previously also round-tripped a
 * {@code text[]} column on the now-removed {@code holds} table (dead schema
 * — hold-service is the real owner of holds, see V2 migration); case-service
 * has no array-typed column of its own to substitute, so array round-tripping
 * is no longer covered here.
 */
public class CaseSchemaRepository {
    private final JdbcTemplate jdbc;

    public CaseSchemaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveFixture(UUID id, String query) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO cases (id, name, description, matter_type, owner, status)
                    VALUES (?, ?, ?, ?::matter_type, ?, ?::case_status)
                    """);
            statement.setObject(1, id);
            statement.setString(2, "Schema test case");
            statement.setString(3, "Postgres type round trip");
            statement.setString(4, "INVESTIGATION");
            statement.setString(5, "schema-test");
            statement.setString(6, "ACTIVE");
            return statement;
        });
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO saved_searches (id, case_id, name, spec)
                    VALUES (?, ?, ?, ?::jsonb)
                    """);
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, id);
            statement.setString(3, "Schema test search");
            statement.setObject(4, query, Types.OTHER);
            return statement;
        });
    }

    public CaseSchemaSnapshot findFixture(UUID caseId) {
        return jdbc.queryForObject("""
                SELECT c.status::text, s.spec->>'q'
                FROM cases c
                JOIN saved_searches s ON s.case_id = c.id
                WHERE c.id = ?
                """, (rs, rowNum) -> new CaseSchemaSnapshot(
                rs.getString(1), rs.getString(2)), caseId);
    }

    public record CaseSchemaSnapshot(String status, String query) {}
}
