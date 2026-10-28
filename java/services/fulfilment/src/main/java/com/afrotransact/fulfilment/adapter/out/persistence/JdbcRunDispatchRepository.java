package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.RunBag;
import com.afrotransact.fulfilment.domain.RunDispatch;
import com.afrotransact.fulfilment.domain.RunDispatchStatus;
import com.afrotransact.fulfilment.port.RunDispatchRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunDispatchRepository implements RunDispatchRepository {

    private final JdbcClient jdbc;
    private final Json json;

    JdbcRunDispatchRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void upsert(RunDispatch r) {
        Instant now = Instant.now();
        jdbc.sql("""
                insert into run_dispatch (run_id, town, state, time_zone, service_date, window_start, window_end,
                    seller_pickup_at, doorstep, pickup_point, origin_lat, origin_lon, depot_node_id, status,
                    plan_error, planned_at, created_at, updated_at)
                values (:id, :town, :state, :zone, :day, :start, :end, :pickup, :doorstep, cast(:point as jsonb),
                    :lat, :lon, :depot, :status, :error, :planned, :now, :now)
                on conflict (run_id) do update set town = excluded.town, state = excluded.state,
                    time_zone = excluded.time_zone, service_date = excluded.service_date,
                    window_start = excluded.window_start, window_end = excluded.window_end,
                    seller_pickup_at = excluded.seller_pickup_at, doorstep = excluded.doorstep,
                    pickup_point = excluded.pickup_point, origin_lat = excluded.origin_lat,
                    origin_lon = excluded.origin_lon, updated_at = excluded.updated_at
                """)
                .param("id", r.runId()).param("town", r.town()).param("state", r.state())
                .param("zone", r.timeZone()).param("day", Rows.date(r.serviceDate()))
                .param("start", Rows.ts(r.windowStart())).param("end", Rows.ts(r.windowEnd()))
                .param("pickup", Rows.ts(r.sellerPickupAt())).param("doorstep", r.doorstep())
                .param("point", json.write(r.pickupPoint())).param("lat", Rows.lat(r.origin()))
                .param("lon", Rows.lon(r.origin())).param("depot", r.depotNodeId()).param("status", r.status().name())
                .param("error", r.planError()).param("planned", Rows.ts(r.plannedAt())).param("now", Rows.ts(now))
                .update();
    }

    @Override
    public void save(RunDispatch r) {
        jdbc.sql("""
                update run_dispatch set status = :status, plan_error = :error, depot_node_id = :depot,
                    planned_at = :planned, updated_at = now(), version = version + 1
                where run_id = :id and version = :version
                """)
                .param("status", r.status().name()).param("error", truncate(r.planError()))
                .param("depot", r.depotNodeId()).param("planned", Rows.ts(r.plannedAt())).param("id", r.runId())
                .param("version", r.version()).update();
    }

    @Override
    public Optional<RunDispatch> find(String runId) {
        return jdbc.sql("select * from run_dispatch where run_id = :id").param("id", runId).query(this::map).optional();
    }

    @Override
    public Optional<RunDispatch> lock(String runId) {
        return jdbc.sql("select * from run_dispatch where run_id = :id for update").param("id", runId)
                .query(this::map).optional();
    }

    @Override
    public List<RunDispatch> list(RunDispatchStatus status, int limit) {
        return jdbc.sql("""
                select * from run_dispatch where (cast(:status as varchar) is null or status = :status)
                order by service_date desc, window_start desc, run_id limit :limit
                """)
                .param("status", status == null ? null : status.name()).param("limit", limit).query(this::map).list();
    }

    @Override
    public void linkRoute(String runId, String routeId, int seq) {
        jdbc.sql("insert into run_route (run_id, route_id, seq) values (:run, :route, :seq) on conflict do nothing")
                .param("run", runId).param("route", routeId).param("seq", seq).update();
    }

    @Override
    public List<String> routeIds(String runId) {
        return jdbc.sql("select route_id from run_route where run_id = :id order by seq").param("id", runId)
                .query(String.class).list();
    }

    @Override
    public Optional<String> runOfRoute(String routeId) {
        return jdbc.sql("select run_id from run_route where route_id = :id").param("id", routeId)
                .query(String.class).optional();
    }

    @Override
    public void linkShipment(String runId, String shipmentId) {
        jdbc.sql("""
                insert into run_shipment (shipment_id, run_id) values (:shipment, :run)
                on conflict (shipment_id) do update set run_id = excluded.run_id
                """).param("shipment", shipmentId).param("run", runId).update();
    }

    @Override
    public List<String> shipmentIds(String runId) {
        return jdbc.sql("select shipment_id from run_shipment where run_id = :id order by shipment_id")
                .param("id", runId).query(String.class).list();
    }

    @Override
    public Optional<String> runOfShipment(String shipmentId) {
        return jdbc.sql("select run_id from run_shipment where shipment_id = :id").param("id", shipmentId)
                .query(String.class).optional();
    }

    @Override
    public void insertBags(List<RunBag> bags) {
        for (RunBag b : bags) {
            jdbc.sql("""
                    insert into shipment_bag (shipment_id, seller_org_id, seller_name, label, units)
                    values (:shipment, :seller, :name, :label, :units) on conflict do nothing
                    """)
                    .param("shipment", b.shipmentId()).param("seller", b.sellerOrgId()).param("name", b.sellerName())
                    .param("label", b.label()).param("units", b.units()).update();
        }
    }

    @Override
    public Map<String, List<RunBag>> bags(Collection<String> shipmentIds) {
        Map<String, List<RunBag>> out = new LinkedHashMap<>();
        if (shipmentIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select shipment_id, seller_org_id, seller_name, label, units from shipment_bag
                where shipment_id in (:ids) order by shipment_id, label
                """).param("ids", shipmentIds).query((rs, n) -> new RunBag(rs.getString("shipment_id"),
                rs.getString("seller_org_id"), rs.getString("seller_name"), rs.getString("label"),
                rs.getInt("units"))).list()
                .forEach(b -> out.computeIfAbsent(b.shipmentId(), k -> new ArrayList<>()).add(b));
        return out;
    }

    private RunDispatch map(ResultSet rs, int n) throws SQLException {
        return new RunDispatch(rs.getString("run_id"), rs.getString("town"), rs.getString("state"),
                rs.getString("time_zone"), Rows.localDate(rs, "service_date"), Rows.instant(rs, "window_start"),
                Rows.instant(rs, "window_end"), Rows.instant(rs, "seller_pickup_at"), rs.getBoolean("doorstep"),
                json.pickupPoint(rs.getString("pickup_point")), Rows.point(rs, "origin_lat", "origin_lon"),
                rs.getString("depot_node_id"), RunDispatchStatus.valueOf(rs.getString("status")),
                rs.getString("plan_error"), Rows.instant(rs, "planned_at"), rs.getInt("version"));
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
