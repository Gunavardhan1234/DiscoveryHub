package com.smarsh.discoveryhub.hold.infrastructure.lock;

import com.smarsh.discoveryhub.hold.domain.disposition.DispositionLock;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code pg_try_advisory_lock} never blocks: a second runner gets {@code
 * empty()} rather than waiting. Mocked JDBC — no real Postgres needed.
 */
class PostgresAdvisoryLockTest {

    private DataSource dataSource(Connection connection) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }

    private Connection connectionReturning(boolean lockAcquired) throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(lockAcquired);
        return connection;
    }

    @Test
    void tryAcquireReturnsAHandleWhenTheLockIsFree() throws Exception {
        Connection connection = connectionReturning(true);
        PostgresAdvisoryLock lock = new PostgresAdvisoryLock(dataSource(connection));

        Optional<DispositionLock.LockHandle> handle = lock.tryAcquire();

        assertThat(handle).isPresent();
        verify(connection, never()).close();
    }

    @Test
    void tryAcquireReturnsEmptyWithoutBlockingWhenAlreadyHeld() throws Exception {
        Connection connection = connectionReturning(false);
        PostgresAdvisoryLock lock = new PostgresAdvisoryLock(dataSource(connection));

        Optional<DispositionLock.LockHandle> handle = lock.tryAcquire();

        assertThat(handle).isEmpty();
        // Not holding the lock — the connection is released immediately.
        verify(connection).close();
    }

    @Test
    void closingTheHandleReleasesTheLockAndClosesTheConnection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement acquireStatement = mock(PreparedStatement.class);
        ResultSet acquireResult = mock(ResultSet.class);
        when(acquireResult.next()).thenReturn(true);
        when(acquireResult.getBoolean(1)).thenReturn(true);
        when(acquireStatement.executeQuery()).thenReturn(acquireResult);

        PreparedStatement releaseStatement = mock(PreparedStatement.class);
        ResultSet releaseResult = mock(ResultSet.class);
        when(releaseResult.next()).thenReturn(true);
        when(releaseResult.getBoolean(1)).thenReturn(true);
        when(releaseStatement.executeQuery()).thenReturn(releaseResult);

        // First prepareStatement() call is the acquire; second is the release.
        when(connection.prepareStatement(anyString())).thenReturn(acquireStatement, releaseStatement);
        PostgresAdvisoryLock lock = new PostgresAdvisoryLock(dataSource(connection));
        DispositionLock.LockHandle handle = lock.tryAcquire().orElseThrow();

        handle.close();

        verify(connection, times(1)).close();
    }

    @Test
    void acquireFailureClosesTheConnectionAndThrows() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("connection reset"));
        PostgresAdvisoryLock lock = new PostgresAdvisoryLock(dataSource(connection));

        assertThatThrownBy(lock::tryAcquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to acquire disposition advisory lock");
        verify(connection).close();
    }

    @Test
    void releaseFailureIsSwallowedAndStillClosesTheConnection() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement acquireStatement = mock(PreparedStatement.class);
        ResultSet acquireResult = mock(ResultSet.class);
        when(acquireResult.next()).thenReturn(true);
        when(acquireResult.getBoolean(1)).thenReturn(true);
        when(acquireStatement.executeQuery()).thenReturn(acquireResult);

        when(connection.prepareStatement(anyString()))
                .thenReturn(acquireStatement)
                .thenThrow(new SQLException("release failed"));
        PostgresAdvisoryLock lock = new PostgresAdvisoryLock(dataSource(connection));
        DispositionLock.LockHandle handle = lock.tryAcquire().orElseThrow();

        handle.close();

        verify(connection).close();
    }
}
