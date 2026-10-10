package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import com.afrotransact.fulfilment.domain.GeoPoint;

/** JDBC conversions for nullable columns. */
final class Rows {

    private Rows() {
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static LocalDate localDate(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    static Integer integer(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static GeoPoint point(ResultSet rs, String lat, String lon) throws SQLException {
        return GeoPoint.of(dbl(rs, lat), dbl(rs, lon));
    }

    static Double lat(GeoPoint p) {
        return p == null ? null : p.lat();
    }

    static Double lon(GeoPoint p) {
        return p == null ? null : p.lon();
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
