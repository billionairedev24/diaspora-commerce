package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.port.DriverEventLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDriverEventLog implements DriverEventLog {

    private final JdbcClient jdbc;

    JdbcDriverEventLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long lockDevice(String deviceId, String driverId, Instant now) {
        jdbc.sql("""
                insert into driver_device (device_id, driver_id, last_sequence, last_seen_at)
                values (:device, :driver, 0, :now)
                on conflict (device_id) do nothing
                """).param("device", deviceId).param("driver", driverId).param("now", Rows.ts(now)).update();
        return jdbc.sql("select last_sequence from driver_device where device_id = :device for update")
                .param("device", deviceId).query(Long.class).single();
    }

    @Override
    public void advance(String deviceId, long sequence, Instant now) {
        jdbc.sql("""
                update driver_device set last_sequence = greatest(last_sequence, :seq), last_seen_at = :now
                where device_id = :device
                """).param("seq", sequence).param("now", Rows.ts(now)).param("device", deviceId).update();
    }

    @Override
    public long lastSequence(String deviceId) {
        return jdbc.sql("select last_sequence from driver_device where device_id = :device")
                .param("device", deviceId).query(Long.class).optional().orElse(0L);
    }

    @Override
    public Optional<Entry> find(String eventId) {
        return jdbc.sql("select * from driver_event where event_id = :id").param("id", eventId).query(this::map)
                .optional();
    }

    @Override
    public void record(Entry e) {
        jdbc.sql("""
                insert into driver_event (event_id, device_id, sequence, driver_id, type, route_id, stop_id,
                    captured_at, received_at, outcome, code, message, payload)
                values (:id, :device, :seq, :driver, :type, :route, :stop, :captured, :received, :outcome, :code,
                    :message, cast(:payload as jsonb))
                """)
                .param("id", e.eventId()).param("device", e.deviceId()).param("seq", e.sequence())
                .param("driver", e.driverId()).param("type", truncate(e.type(), 40))
                .param("route", truncate(e.routeId(), 20)).param("stop", truncate(e.stopId(), 64))
                .param("captured", Rows.ts(e.capturedAt())).param("received", Rows.ts(e.receivedAt()))
                .param("outcome", e.outcome()).param("code", truncate(e.code(), 80))
                .param("message", truncate(e.message(), 1000)).param("payload", e.payloadJson())
                .update();
    }

    @Override
    public List<Entry> recent(String routeId, String driverId, int limit) {
        return jdbc.sql("""
                select * from driver_event
                where (cast(:route as varchar) is null or route_id = :route)
                  and (cast(:driver as varchar) is null or driver_id = :driver)
                order by received_at desc, sequence desc limit :limit
                """).param("route", routeId).param("driver", driverId).param("limit", limit).query(this::map).list();
    }

    private Entry map(ResultSet rs, int n) throws SQLException {
        return new Entry(rs.getString("event_id"), rs.getString("device_id"), rs.getLong("sequence"),
                rs.getString("driver_id"), rs.getString("type"), rs.getString("route_id"), rs.getString("stop_id"),
                Rows.instant(rs, "captured_at"), Rows.instant(rs, "received_at"), rs.getString("outcome"),
                rs.getString("code"), rs.getString("message"), rs.getString("payload"));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
