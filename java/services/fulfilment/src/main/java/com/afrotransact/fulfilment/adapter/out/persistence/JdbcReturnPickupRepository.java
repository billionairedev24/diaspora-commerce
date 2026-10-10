package com.afrotransact.fulfilment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.ReturnPickup;
import com.afrotransact.fulfilment.domain.ReturnPickupStatus;
import com.afrotransact.fulfilment.port.ReturnPickupRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReturnPickupRepository implements ReturnPickupRepository {

    private final JdbcClient jdbc;

    JdbcReturnPickupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(ReturnPickup p) {
        return jdbc.sql("""
                insert into return_pickup (id, rma_id, rma_number, order_id, order_number, shipment_id, customer_id,
                    seller_org_id, name, phone, line1, line2, city, state, postal_code, country, lat, lon,
                    depot_node_id, ready_from, collect_by, units, status, route_id, stop_id, note, created_at,
                    updated_at)
                values (:id, :rma, :rmaNumber, :order, :orderNumber, :shipment, :customer, :seller, :name, :phone,
                    :line1, :line2, :city, :state, :zip, :country, :lat, :lon, :depot, :readyFrom, :collectBy, :units,
                    :status, :route, :stop, :note, :created, :updated)
                on conflict (rma_id) do nothing
                """)
                .param("id", p.id()).param("rma", p.rmaId()).param("rmaNumber", p.rmaNumber())
                .param("order", p.orderId()).param("orderNumber", p.orderNumber()).param("shipment", p.shipmentId())
                .param("customer", p.customerId()).param("seller", p.sellerOrgId()).param("name", p.name())
                .param("phone", p.phone()).param("line1", p.line1()).param("line2", p.line2())
                .param("city", p.city()).param("state", p.state()).param("zip", p.postalCode())
                .param("country", p.country()).param("lat", Rows.lat(p.location()))
                .param("lon", Rows.lon(p.location())).param("depot", p.depotNodeId())
                .param("readyFrom", Rows.date(p.readyFrom())).param("collectBy", Rows.date(p.collectBy()))
                .param("units", p.units()).param("status", p.status().name()).param("route", p.routeId())
                .param("stop", p.stopId()).param("note", p.note()).param("created", Rows.ts(p.createdAt()))
                .param("updated", Rows.ts(p.updatedAt()))
                .update() == 1;
    }

    @Override
    public Optional<ReturnPickup> find(String id) {
        return jdbc.sql("select * from return_pickup where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<ReturnPickup> findByRma(String rmaId) {
        return jdbc.sql("select * from return_pickup where rma_id = :id").param("id", rmaId).query(this::map)
                .optional();
    }

    @Override
    public Optional<ReturnPickup> findByStop(String stopId) {
        return jdbc.sql("select * from return_pickup where stop_id = :id").param("id", stopId).query(this::map)
                .optional();
    }

    @Override
    public Optional<ReturnPickup> lock(String id) {
        return jdbc.sql("select * from return_pickup where id = :id for update").param("id", id).query(this::map)
                .optional();
    }

    @Override
    public List<ReturnPickup> list(ReturnPickupStatus status, String depotNodeId, int limit) {
        return jdbc.sql("""
                select * from return_pickup
                where (cast(:status as varchar) is null or status = :status)
                  and (cast(:depot as varchar) is null or depot_node_id = :depot)
                order by collect_by nulls last, created_at limit :limit
                """).param("status", status == null ? null : status.name()).param("depot", depotNodeId)
                .param("limit", limit).query(this::map).list();
    }

    @Override
    public void update(ReturnPickup p) {
        jdbc.sql("""
                update return_pickup set status = :status, route_id = :route, stop_id = :stop, note = :note,
                    updated_at = :updated
                where id = :id
                """).param("status", p.status().name()).param("route", p.routeId()).param("stop", p.stopId())
                .param("note", p.note()).param("updated", Rows.ts(p.updatedAt())).param("id", p.id()).update();
    }

    private ReturnPickup map(ResultSet rs, int row) throws SQLException {
        return new ReturnPickup(rs.getString("id"), rs.getString("rma_id"), rs.getString("rma_number"),
                rs.getString("order_id"), rs.getString("order_number"), rs.getString("shipment_id"),
                rs.getString("customer_id"), rs.getString("seller_org_id"), rs.getString("name"),
                rs.getString("phone"), rs.getString("line1"), rs.getString("line2"), rs.getString("city"),
                rs.getString("state"), rs.getString("postal_code"), rs.getString("country"),
                Rows.point(rs, "lat", "lon"), rs.getString("depot_node_id"), Rows.localDate(rs, "ready_from"),
                Rows.localDate(rs, "collect_by"), rs.getInt("units"),
                ReturnPickupStatus.valueOf(rs.getString("status")), rs.getString("route_id"),
                rs.getString("stop_id"), rs.getString("note"), Rows.instant(rs, "created_at"),
                Rows.instant(rs, "updated_at"));
    }
}
