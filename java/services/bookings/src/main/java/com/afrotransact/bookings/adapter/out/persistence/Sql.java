package com.afrotransact.bookings.adapter.out.persistence;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Small JDBC helpers shared by the repositories. */
final class Sql {

    /** Great-circle miles from a.lat/a.lon (alias {@code a}) to the bound :lat/:lon. */
    static final String HAVERSINE_MILES = "(7917.6 * asin(sqrt(least(1, power(sin(radians(a.lat - :lat) / 2), 2)"
            + " + cos(radians(:lat)) * cos(radians(a.lat)) * power(sin(radians(a.lon - :lon) / 2), 2)))))";

    private Sql() {
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** True when {@code e} (or a cause) is a PostgreSQL error with {@code sqlState}. */
    static boolean hasState(Throwable e, String sqlState) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
