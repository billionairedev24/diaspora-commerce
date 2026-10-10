package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers for JdbcClient repositories: optional filters, paging and null-safe conversions. */
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

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /** Accumulates optional {@code column = :param} conditions. */
    static final class Where {

        private final List<String> conditions = new ArrayList<>();
        private final Map<String, Object> params = new LinkedHashMap<>();

        Where eq(String column, String param, Object value) {
            if (value != null) {
                conditions.add(column + " = :" + param);
                params.put(param, value);
            }
            return this;
        }

        Where raw(String condition, String param, Object value) {
            if (value != null) {
                conditions.add(condition);
                params.put(param, value);
            }
            return this;
        }

        String sql() {
            return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
        }

        Map<String, Object> params() {
            return params;
        }
    }
}
