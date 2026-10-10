package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.HandoverMethod;
import com.afrotransact.fulfilment.domain.InspectionResult;
import com.afrotransact.fulfilment.domain.PackageOrigin;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RouteStatus;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.StopKind;
import com.afrotransact.fulfilment.domain.StopStatus;
import com.afrotransact.fulfilment.port.RouteRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Routes are written as one aggregate: the route row (version + 1) and an upsert of every stop and package. Driver
 * events and dispatch actions take the row lock ({@code select … for update}) first, so they never interleave.
 */
@Repository
class JdbcRouteRepository implements RouteRepository {

    private final JdbcClient jdbc;
    private final Json json;

    JdbcRouteRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private record RouteRow(String id, String name, String zone, LocalDate serviceDate, String window, String depot,
                            String driverId, String vehicleId, RouteStatus status, InspectionResult inspection,
                            boolean blocked, String blockedReason, Integer startOdometer, Integer endOdometer,
                            Instant loadOutAt, Instant startedAt, Instant endedAt,
                            int version) {
    }

    @Override
    public void insert(DeliveryRoute r) {
        jdbc.sql("""
                insert into delivery_route (id, name, zone, service_date, time_window, depot_node_id, driver_id,
                    vehicle_id, status)
                values (:id, :name, :zone, :date, :window, :depot, :driver, :vehicle, :status)
                """)
                .param("id", r.id()).param("name", r.name()).param("zone", r.zone())
                .param("date", Rows.date(r.serviceDate())).param("window", r.window())
                .param("depot", r.depotNodeId()).param("driver", r.driverId()).param("vehicle", r.vehicleId())
                .param("status", r.status().name())
                .update();
        saveChildren(r);
    }

    @Override
    public Optional<DeliveryRoute> lock(String routeId) {
        return load(routeId, " for update");
    }

    @Override
    public Optional<DeliveryRoute> find(String routeId) {
        return load(routeId, "");
    }

    @Override
    public void save(DeliveryRoute r) {
        jdbc.sql("""
                update delivery_route set driver_id = :driver, vehicle_id = :vehicle, status = :status,
                    inspection = :inspection, blocked = :blocked, blocked_reason = :reason,
                    start_odometer = :startOdo, end_odometer = :endOdo, load_out_completed_at = :loadOut,
                    started_at = :started, ended_at = :ended, updated_at = now(), version = version + 1
                where id = :id
                """)
                .param("driver", r.driverId()).param("vehicle", r.vehicleId()).param("status", r.status().name())
                .param("inspection", r.inspection() == null ? null : r.inspection().name())
                .param("blocked", r.blocked()).param("reason", r.blockedReason())
                .param("startOdo", r.startOdometer()).param("endOdo", r.endOdometer())
                .param("loadOut", Rows.ts(r.loadOutCompletedAt())).param("started", Rows.ts(r.startedAt()))
                .param("ended", Rows.ts(r.endedAt())).param("id", r.id())
                .update();
        saveChildren(r);
    }

    @Override
    public List<DeliveryRoute> list(LocalDate date, RouteStatus status, String driverId, int limit) {
        List<String> ids = jdbc.sql("""
                select id from delivery_route
                where (cast(:date as date) is null or service_date = :date)
                  and (cast(:status as varchar) is null or status = :status)
                  and (cast(:driver as varchar) is null or driver_id = :driver)
                order by service_date desc, id limit :limit
                """)
                .param("date", Rows.date(date)).param("status", status == null ? null : status.name())
                .param("driver", driverId).param("limit", limit)
                .query(String.class).list();
        return ids.stream().map(id -> find(id).orElseThrow()).toList();
    }

    @Override
    public List<DeliveryRoute> openForDriver(String driverId) {
        List<String> ids = jdbc.sql("""
                select id from delivery_route where driver_id = :driver
                  and status in ('PLANNED', 'ASSIGNED', 'IN_PROGRESS')
                order by service_date, id
                """).param("driver", driverId).query(String.class).list();
        return ids.stream().map(id -> find(id).orElseThrow()).toList();
    }

    @Override
    public Optional<String> routeOfStop(String stopId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(stopId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return jdbc.sql("select route_id from route_stop where id = :id").param("id", uuid).query(String.class)
                .optional();
    }

    // ------------------------------------------------------------------ load

    private Optional<DeliveryRoute> load(String routeId, String lock) {
        Optional<RouteRow> row = jdbc.sql("select * from delivery_route where id = :id" + lock)
                .param("id", routeId).query(this::mapRoute).optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        RouteRow r = row.get();
        List<RouteStop> stops = jdbc.sql("select * from route_stop where route_id = :id order by seq")
                .param("id", routeId).query(this::mapStop).list();
        List<RoutePackage> packages = jdbc.sql("select * from route_package where route_id = :id order by label")
                .param("id", routeId).query(this::mapPackage).list();
        return Optional.of(new DeliveryRoute(r.id(), r.name(), r.zone(), r.serviceDate(), r.window(), r.depot(),
                r.driverId(), r.vehicleId(), r.status(), r.inspection(), r.blocked(), r.blockedReason(),
                r.startOdometer(), r.endOdometer(), r.loadOutAt(), r.startedAt(), r.endedAt(), r.version(), stops,
                packages));
    }

    private RouteRow mapRoute(ResultSet rs, int n) throws SQLException {
        return new RouteRow(rs.getString("id"), rs.getString("name"), rs.getString("zone"),
                Rows.localDate(rs, "service_date"), rs.getString("time_window"), rs.getString("depot_node_id"),
                rs.getString("driver_id"), rs.getString("vehicle_id"), RouteStatus.valueOf(rs.getString("status")),
                Rows.enumOf(InspectionResult.class, rs.getString("inspection")), rs.getBoolean("blocked"),
                rs.getString("blocked_reason"), Rows.integer(rs, "start_odometer"), Rows.integer(rs, "end_odometer"),
                Rows.instant(rs, "load_out_completed_at"), Rows.instant(rs, "started_at"),
                Rows.instant(rs, "ended_at"), rs.getInt("version"));
    }

    private RouteStop mapStop(ResultSet rs, int n) throws SQLException {
        return new RouteStop(rs.getString("id"), StopKind.valueOf(rs.getString("kind")), rs.getInt("seq"),
                rs.getString("name"), rs.getString("seller_org_id"), rs.getString("order_number"),
                rs.getString("address_line"), rs.getString("city"), rs.getString("postal_code"),
                Rows.point(rs, "lat", "lon"), rs.getString("time_window"), rs.getBoolean("handover_required"),
                rs.getBoolean("safe_place_allowed"), StopStatus.valueOf(rs.getString("status")),
                rs.getInt("reattempts"), json.contacts(rs.getString("contacts")), rs.getBoolean("partial_accepted"),
                rs.getString("handed_over_by"), rs.getString("note"),
                Rows.enumOf(HandoverMethod.class, rs.getString("handover_method")), Rows.dbl(rs, "pod_distance_m"),
                rs.getString("reassigned_to"), Rows.instant(rs, "updated_at"), rs.getString("pickup_location_id"));
    }

    private RoutePackage mapPackage(ResultSet rs, int n) throws SQLException {
        return new RoutePackage(rs.getString("id"), rs.getString("label"), rs.getString("shipment_id"),
                rs.getString("order_number"), PackageOrigin.valueOf(rs.getString("origin")),
                rs.getString("pickup_stop_id"), rs.getString("dropoff_stop_id"), rs.getBoolean("perishable"),
                PackageStatus.valueOf(rs.getString("status")), rs.getBoolean("pickup_scanned"),
                rs.getBoolean("drop_scanned"), rs.getBoolean("manual_entry"), Rows.instant(rs, "updated_at"));
    }

    // ------------------------------------------------------------------ save

    private void saveChildren(DeliveryRoute r) {
        for (RouteStop s : r.stops()) {
            jdbc.sql("""
                    insert into route_stop (id, route_id, seq, kind, name, seller_org_id, order_number,
                        address_line, city, postal_code, lat, lon, time_window, handover_required,
                        safe_place_allowed, status, reattempts, contacts, partial_accepted, handed_over_by, note,
                        handover_method, pod_distance_m, reassigned_to, updated_at, pickup_location_id)
                    values (:id, :route, :seq, :kind, :name, :seller, :order, :line, :city, :zip, :lat, :lon,
                        :window, :handover, :safe, :status, :reattempts, cast(:contacts as jsonb), :partial,
                        :handedOverBy, :note, :method, :distance, :reassigned, :at, :pickupLocation)
                    on conflict (id) do update set seq = excluded.seq, status = excluded.status,
                        reattempts = excluded.reattempts, contacts = excluded.contacts,
                        partial_accepted = excluded.partial_accepted, handed_over_by = excluded.handed_over_by,
                        note = excluded.note, handover_method = excluded.handover_method,
                        pod_distance_m = excluded.pod_distance_m, reassigned_to = excluded.reassigned_to,
                        updated_at = excluded.updated_at
                    """)
                    .param("id", UUID.fromString(s.id())).param("route", r.id()).param("seq", s.sequence())
                    .param("kind", s.kind().name()).param("name", s.name()).param("seller", s.sellerOrgId())
                    .param("order", s.orderNumber()).param("line", s.addressLine()).param("city", s.city())
                    .param("zip", s.postalCode()).param("lat", Rows.lat(s.point())).param("lon", Rows.lon(s.point()))
                    .param("window", s.window()).param("handover", s.handoverRequired())
                    .param("safe", s.safePlaceAllowed()).param("status", s.status().name())
                    .param("reattempts", s.reattempts()).param("contacts", json.write(s.contacts()))
                    .param("partial", s.partialAccepted()).param("handedOverBy", s.handedOverBy())
                    .param("note", s.note())
                    .param("method", s.handoverMethod() == null ? null : s.handoverMethod().name())
                    .param("distance", s.podDistanceMeters())
                    .param("reassigned", s.reassignedTo() == null ? null : UUID.fromString(s.reassignedTo()))
                    .param("at", Rows.ts(s.updatedAt()))
                    .param("pickupLocation", s.pickupLocationId())
                    .update();
        }
        for (RoutePackage p : r.packages()) {
            jdbc.sql("""
                    insert into route_package (id, route_id, label, shipment_id, order_number, origin,
                        pickup_stop_id, dropoff_stop_id, perishable, status, pickup_scanned, drop_scanned,
                        manual_entry, updated_at)
                    values (:id, :route, :label, :shipment, :order, :origin, :pickup, :drop, :perishable, :status,
                        :pickupScanned, :dropScanned, :manual, :at)
                    on conflict (id) do update set status = excluded.status,
                        pickup_scanned = excluded.pickup_scanned, drop_scanned = excluded.drop_scanned,
                        manual_entry = excluded.manual_entry, updated_at = excluded.updated_at
                    """)
                    .param("id", UUID.fromString(p.id())).param("route", r.id()).param("label", p.label())
                    .param("shipment", p.shipmentId()).param("order", p.orderNumber())
                    .param("origin", p.origin().name())
                    .param("pickup", p.pickupStopId() == null ? null : UUID.fromString(p.pickupStopId()))
                    .param("drop", UUID.fromString(p.dropoffStopId())).param("perishable", p.perishable())
                    .param("status", p.status().name()).param("pickupScanned", p.pickupScanned())
                    .param("dropScanned", p.dropScanned()).param("manual", p.manualEntry())
                    .param("at", Rows.ts(p.updatedAt()))
                    .update();
        }
    }
}
