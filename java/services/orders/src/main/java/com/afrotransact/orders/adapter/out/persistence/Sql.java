package com.afrotransact.orders.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Small JDBC conversions. */
final class Sql {

    private Sql() {
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    static UUID uuid(String id) {
        return id == null ? null : UUID.fromString(id);
    }

    static String str(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return v == null ? null : v.toString();
    }
}
