package com.afrotransact.fulfilment.adapter.out.persistence;

import java.time.Instant;
import java.util.List;

import com.afrotransact.fulfilment.domain.SosAlert;
import com.afrotransact.fulfilment.domain.SosKind;
import com.afrotransact.fulfilment.port.SosRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSosRepository implements SosRepository {

    private final JdbcClient jdbc;

    JdbcSosRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(SosAlert a) {
        return jdbc.sql("""
                insert into sos_alert (event_id, driver_id, route_id, kind, lat, lon, note, raised_at, received_at)
                values (:id, :driver, :route, :kind, :lat, :lon, :note, :raised, :received)
                on conflict (event_id) do nothing
                """)
                .param("id", a.eventId()).param("driver", a.driverId()).param("route", a.routeId())
                .param("kind", a.kind().name()).param("lat", Rows.lat(a.location()))
                .param("lon", Rows.lon(a.location())).param("note", a.note())
                .param("raised", Rows.ts(a.raisedAt())).param("received", Rows.ts(a.receivedAt()))
                .update() == 1;
    }

    @Override
    public List<SosAlert> open() {
        return jdbc.sql("select * from sos_alert where acknowledged_at is null order by received_at")
                .query((rs, n) -> new SosAlert(rs.getString("event_id"), rs.getString("driver_id"),
                        rs.getString("route_id"), SosKind.valueOf(rs.getString("kind")), Rows.point(rs, "lat", "lon"),
                        rs.getString("note"), Rows.instant(rs, "raised_at"), Rows.instant(rs, "received_at"),
                        rs.getString("acknowledged_by"), Rows.instant(rs, "acknowledged_at")))
                .list();
    }

    @Override
    public boolean acknowledge(String eventId, String by, Instant at) {
        return jdbc.sql("""
                update sos_alert set acknowledged_by = :by, acknowledged_at = :at
                where event_id = :id and acknowledged_at is null
                """).param("by", by).param("at", Rows.ts(at)).param("id", eventId).update() == 1;
    }
}
