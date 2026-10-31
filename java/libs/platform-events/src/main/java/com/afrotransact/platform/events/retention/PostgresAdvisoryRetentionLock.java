package com.afrotransact.platform.events.retention;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * PostgreSQL session advisory lock. The repository has no scheduled-job lock library, so this holds one dedicated
 * connection for the length of the run (the purge itself uses the normal pool) and releases the lock explicitly. If
 * the instance dies, the server drops the lock with the session.
 */
class PostgresAdvisoryRetentionLock implements RetentionRunLock {

    /** "ATEVRETN" as a number: one lock id for the retention job in every service database. */
    static final long LOCK_ID = 0x41544556_5245544EL;

    private final DataSource dataSource;

    PostgresAdvisoryRetentionLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> Optional<T> tryRun(Supplier<T> action) {
        try (Connection lockSession = dataSource.getConnection()) {
            if (!tryLock(lockSession)) {
                return Optional.empty();
            }
            try {
                return Optional.ofNullable(action.get());
            } finally {
                unlock(lockSession);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not take the retention lock", ex);
        }
    }

    private static boolean tryLock(Connection c) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("select pg_try_advisory_lock(?)")) {
            st.setLong(1, LOCK_ID);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection c) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("select pg_advisory_unlock(?)")) {
            st.setLong(1, LOCK_ID);
            st.execute();
        }
    }
}
