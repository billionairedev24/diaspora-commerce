package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;
import com.afrotransact.fulfilment.domain.PackageOrigin;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDeliveryShipmentRepository implements DeliveryShipmentRepository {

    private final JdbcClient jdbc;

    JdbcDeliveryShipmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(DeliveryShipment s) {
        return jdbc.sql("""
                insert into delivery_shipment (shipment_id, order_id, order_number, customer_id, seller_org_id,
                    seller_name, fulfilment_type, origin, node_id, label, city, state, postal_code, country, lat,
                    lon, perishable, handover_required, units, status, route_id, promised_by, created_at, ship_by,
                    pickup_location_id, pickup_lat, pickup_lon)
                values (:id, :order, :number, :customer, :seller, :sellerName, :type, :origin, :node, :label, :city,
                    :state, :zip, :country, :lat, :lon, :perishable, :handover, :units, :status, :route, :promised,
                    :created, :shipBy, :pickupId, :pickupLat, :pickupLon)
                on conflict (shipment_id) do nothing
                """)
                .param("id", s.shipmentId()).param("order", s.orderId()).param("number", s.orderNumber())
                .param("customer", s.customerId()).param("seller", s.sellerOrgId())
                .param("sellerName", s.sellerName()).param("type", s.fulfilmentType())
                .param("origin", s.origin().name()).param("node", s.nodeId()).param("label", s.label())
                .param("city", s.city()).param("state", s.state()).param("zip", s.postalCode())
                .param("country", s.country()).param("lat", Rows.lat(s.location()))
                .param("lon", Rows.lon(s.location())).param("perishable", s.perishable())
                .param("handover", s.handoverRequired()).param("units", s.units())
                .param("status", s.status().name()).param("route", s.routeId())
                .param("promised", Rows.date(s.promisedBy())).param("created", Rows.ts(s.createdAt()))
                .param("shipBy", Rows.date(s.shipBy())).param("pickupId", s.pickupLocationId())
                .param("pickupLat", Rows.lat(s.pickupPoint())).param("pickupLon", Rows.lon(s.pickupPoint()))
                .update() == 1;
    }

    @Override
    public Optional<DeliveryShipment> find(String shipmentId) {
        return jdbc.sql("select * from delivery_shipment where shipment_id = :id").param("id", shipmentId)
                .query(this::map).optional();
    }

    @Override
    public List<DeliveryShipment> lockAll(List<String> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select * from delivery_shipment where shipment_id in (:ids) order by shipment_id for update
                """).param("ids", shipmentIds).query(this::map).list();
    }

    @Override
    public List<DeliveryShipment> awaitingRoute(String nodeId, int limit) {
        return jdbc.sql("""
                select * from delivery_shipment
                where status = 'AWAITING_ROUTE' and fulfilment_type <> 'PREORDER'
                  and (cast(:node as varchar) is null or origin = 'SELLER' or node_id = :node or node_id is null)
                order by promised_by nulls last, created_at limit :limit
                """).param("node", nodeId).param("limit", limit).query(this::map).list();
    }

    @Override
    public List<DeliveryShipment> findAllById(Collection<String> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from delivery_shipment where shipment_id in (:ids) order by shipment_id")
                .param("ids", shipmentIds).query(this::map).list();
    }

    @Override
    public List<DeliveryShipment> findByOrderIds(Collection<String> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select * from delivery_shipment where order_id in (:ids) order by shipment_id")
                .param("ids", orderIds).query(this::map).list();
    }

    @Override
    public void updateStatus(String shipmentId, DeliveryShipmentStatus status, String routeId) {
        jdbc.sql("""
                update delivery_shipment set status = :status, route_id = :route, updated_at = now()
                where shipment_id = :id
                """).param("status", status.name()).param("route", routeId).param("id", shipmentId).update();
    }

    @Override
    public void updateLabel(String shipmentId, String label) {
        jdbc.sql("update delivery_shipment set label = :label, updated_at = now() where shipment_id = :id")
                .param("label", label).param("id", shipmentId).update();
    }

    @Override
    public List<PickupListing> pickups(PickupQuery q) {
        StringBuilder sql = new StringBuilder("""
                select s.*, r.name as route_name, r.driver_id as route_driver_id, r.service_date as route_date,
                       r.status as route_status, p.pickup_stop_id as p_stop_id, st.status as p_stop_status,
                       p.status as p_status
                from delivery_shipment s
                left join delivery_route r on r.id = s.route_id
                left join route_package p on p.shipment_id = s.shipment_id and p.route_id = s.route_id
                    and p.status not in ('REASSIGNED', 'CANCELLED')
                left join route_stop st on st.id = p.pickup_stop_id
                where s.origin = 'SELLER' and s.fulfilment_type <> 'PREORDER'
                """);
        Map<String, Object> params = new HashMap<>();
        if (q.routed() == null) {
            sql.append(" and s.status in ('AWAITING_ROUTE', 'ROUTED')");
        } else {
            sql.append(" and s.status = :status");
            params.put("status", q.routed() ? "ROUTED" : "AWAITING_ROUTE");
        }
        if (q.sellerOrgId() != null) {
            sql.append(" and s.seller_org_id = :seller");
            params.put("seller", q.sellerOrgId());
        }
        if (q.pickupLocationId() != null) {
            sql.append(" and s.pickup_location_id = :location");
            params.put("location", q.pickupLocationId());
        }
        if (q.serviceDate() != null) {
            sql.append(" and (s.status = 'AWAITING_ROUTE' or r.service_date = :day)");
            params.put("day", q.serviceDate());
        }
        sql.append(" order by s.status, s.created_at, s.shipment_id limit :limit");
        params.put("limit", q.limit());
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> new PickupListing(map(rs, n),
                rs.getString("route_name"), rs.getString("route_driver_id"), Rows.localDate(rs, "route_date"),
                rs.getString("route_status"), rs.getString("p_stop_id"), rs.getString("p_stop_status"),
                rs.getString("p_status"))).list();
    }

    private DeliveryShipment map(ResultSet rs, int n) throws SQLException {
        return new DeliveryShipment(rs.getString("shipment_id"), rs.getString("order_id"),
                rs.getString("order_number"), rs.getString("customer_id"), rs.getString("seller_org_id"),
                rs.getString("seller_name"), rs.getString("fulfilment_type"),
                PackageOrigin.valueOf(rs.getString("origin")), rs.getString("node_id"), rs.getString("label"),
                rs.getString("city"), rs.getString("state"), rs.getString("postal_code"), rs.getString("country"),
                Rows.point(rs, "lat", "lon"), rs.getBoolean("perishable"), rs.getBoolean("handover_required"),
                rs.getInt("units"), DeliveryShipmentStatus.valueOf(rs.getString("status")), rs.getString("route_id"),
                Rows.localDate(rs, "promised_by"), Rows.instant(rs, "created_at"), Rows.localDate(rs, "ship_by"),
                rs.getString("pickup_location_id"), Rows.point(rs, "pickup_lat", "pickup_lon"));
    }
}
