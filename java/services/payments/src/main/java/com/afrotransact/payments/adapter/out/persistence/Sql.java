package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Small JDBC conversions shared by the repositories. */
final class Sql {

    private Sql() {
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
