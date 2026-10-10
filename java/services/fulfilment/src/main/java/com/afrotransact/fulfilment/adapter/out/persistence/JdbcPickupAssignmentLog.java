package com.afrotransact.fulfilment.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import com.afrotransact.fulfilment.port.PickupAssignmentLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPickupAssignmentLog implements PickupAssignmentLog {

    private final JdbcClient jdbc;

    JdbcPickupAssignmentLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Entry e) {
        jdbc.sql("""
                insert into pickup_assignment (id, shipment_id, route_id, driver_id, mode, reason, previous_route_id,
                                               straight_miles, road_miles, distance_method, actor, created_at)
                values (:id, :shipment, :route, :driver, :mode, :reason, :previous, :straight, :road, :method, :actor,
                        :at)
                """)
                .param("id", e.id()).param("shipment", e.shipmentId()).param("route", e.routeId())
                .param("driver", e.driverId()).param("mode", e.mode()).param("reason", e.reason())
                .param("previous", e.previousRouteId()).param("straight", e.straightMiles())
                .param("road", e.roadMiles()).param("method", e.distanceMethod()).param("actor", e.actor())
                .param("at", Rows.ts(e.createdAt()))
                .update();
    }

    @Override
    public List<Entry> forShipment(String shipmentId) {
        return jdbc.sql("select * from pickup_assignment where shipment_id = :s order by created_at, id")
                .param("s", shipmentId)
                .query((rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getString("shipment_id"),
                        rs.getString("route_id"), rs.getString("driver_id"), rs.getString("mode"),
                        rs.getString("reason"), rs.getString("previous_route_id"),
                        Rows.dbl(rs, "straight_miles"), Rows.dbl(rs, "road_miles"),
                        rs.getString("distance_method"), rs.getString("actor"), Rows.instant(rs, "created_at")))
                .list();
    }
}
