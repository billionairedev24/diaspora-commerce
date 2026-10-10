package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DriverPosition;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.port.DriverPositionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostGIS geography positions; KNN with {@code <->} on the GiST index, then availability filters. */
@Repository
class JdbcDriverPositionRepository implements DriverPositionRepository {

    /** How many nearest fresh positions the KNN scan considers before the availability filters. */
    private static final int KNN_SCAN = 200;

    private final JdbcClient jdbc;

    JdbcDriverPositionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean upsertIfNewer(DriverPosition p) {
        return jdbc.sql("""
                insert into driver_position (driver_id, position, accuracy_m, recorded_at, source, device_id,
                                             updated_at)
                values (:driver, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :accuracy, :at, :source,
                        :device, now())
                on conflict (driver_id) do update set position = excluded.position,
                    accuracy_m = excluded.accuracy_m, recorded_at = excluded.recorded_at, source = excluded.source,
                    device_id = excluded.device_id, updated_at = now()
                where driver_position.recorded_at < excluded.recorded_at
                """)
                .param("driver", p.driverId()).param("lat", p.point().lat()).param("lon", p.point().lon())
                .param("accuracy", p.accuracyM()).param("at", Rows.ts(p.recordedAt())).param("source", p.source())
                .param("device", p.deviceId())
                .update() == 1;
    }

    @Override
    public Optional<DriverPosition> find(String driverId) {
        return jdbc.sql("""
                select driver_id, ST_Y(position::geometry) as lat, ST_X(position::geometry) as lon, accuracy_m,
                       recorded_at, source, device_id
                from driver_position where driver_id = :d
                """).param("d", driverId).query((rs, n) -> {
                    double acc = rs.getDouble("accuracy_m");
                    return new DriverPosition(rs.getString("driver_id"), new GeoPoint(rs.getDouble("lat"),
                            rs.getDouble("lon")), Rows.instant(rs, "recorded_at"), rs.wasNull() ? null : acc,
                            rs.getString("source"), rs.getString("device_id"));
                }).optional();
    }

    @Override
    public List<Candidate> nearestAvailable(GeoPoint pickup, String zone, LocalDate day, Instant freshAfter,
                                            int maxPackages, int limit) {
        return jdbc.sql("""
                with pt as (select ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography as g),
                nearest as (
                    select p.driver_id, p.position, p.recorded_at, p.position <-> pt.g as knn
                    from driver_position p, pt
                    where p.recorded_at >= :fresh
                    order by p.position <-> pt.g
                    limit :scan),
                routes as (
                    select r.id, r.name, r.zone, r.status, r.driver_id,
                           (select count(*) from route_package rp where rp.route_id = r.id
                              and rp.status in ('EXPECTED', 'LOADED', 'PICKED_UP')) as active
                    from delivery_route r
                    where r.driver_id in (select driver_id from nearest)
                      and r.status in ('ASSIGNED', 'IN_PROGRESS') and not r.blocked
                      and r.inspection in ('PASSED', 'PASSED_WITH_DEFECTS')
                      and r.service_date = :day and r.zone = :zone),
                available as (
                    select distinct on (n.driver_id) n.driver_id, ro.id as route_id, ro.name, ro.zone, ro.status,
                           ST_Distance(n.position, pt.g) as metres, n.recorded_at, ro.active, n.knn,
                           ST_Y(n.position::geometry) as lat, ST_X(n.position::geometry) as lon
                    from nearest n
                    join routes ro on ro.driver_id = n.driver_id
                    cross join pt
                    left join driver_access a on a.driver_id = n.driver_id
                    where (a.status is null or a.status <> 'REVOKED') and ro.active < :max
                    order by n.driver_id, ro.active, ro.id)
                select * from available order by knn, driver_id limit :limit
                """)
                .param("lat", pickup.lat()).param("lon", pickup.lon()).param("fresh", Rows.ts(freshAfter))
                .param("scan", KNN_SCAN).param("day", Rows.date(day)).param("zone", zone).param("max", maxPackages)
                .param("limit", limit)
                .query(JdbcDriverPositionRepository::candidate).list();
    }

    private static Candidate candidate(ResultSet rs, int n) throws SQLException {
        return new Candidate(rs.getString("driver_id"), rs.getString("route_id"), rs.getString("name"),
                rs.getString("zone"), rs.getString("status"), rs.getDouble("metres"), Rows.instant(rs, "recorded_at"),
                rs.getInt("active"), new GeoPoint(rs.getDouble("lat"), rs.getDouble("lon")));
    }
}
