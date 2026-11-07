package com.smarsh.discoveryhub.hold.infrastructure.lock;

import com.smarsh.discoveryhub.hold.domain.disposition.DispositionLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * {@link DispositionLock} over {@code pg_try_advisory_lock}. Advisory locks are
 * session-scoped, so the implementation pins one JDBC {@link Connection} for
 * the whole hold: acquire and release happen on the same session, and closing
 * the connection releases the lock even on a crash — no lock can leak past the
 * session. Never blocks: a second runner simply gets {@code empty}.
 */
@Component
public class PostgresAdvisoryLock implements DispositionLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryLock.class);

    /** Cluster-wide key for the disposition runner (arbitrary, but fixed). */
    static final long LOCK_KEY = 42_05_2026L;

    private final DataSource dataSource;

    public PostgresAdvisoryLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<LockHandle> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(true);
            if (selectBoolean(connection, "SELECT pg_try_advisory_lock(?)")) {
                Connection held = connection;
                return Optional.of(() -> releaseQuietly(held));
            }
            connection.close();
            return Optional.empty();
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new IllegalStateException("Failed to acquire disposition advisory lock", e);
        }
    }

    private boolean selectBoolean(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void releaseQuietly(Connection connection) {
        try {
            selectBoolean(connection, "SELECT pg_advisory_unlock(?)");
        } catch (SQLException e) {
            log.warn("Failed to release disposition advisory lock; closing session releases it anyway", e);
        } finally {
            closeQuietly(connection);
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Failed to close advisory-lock connection", e);
            }
        }
    }
}
