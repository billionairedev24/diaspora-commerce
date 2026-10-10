package com.afrotransact.taxreturns.adapter.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.port.OrderSnapshotRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderSnapshotRepository implements OrderSnapshotRepository {

    private final JdbcClient jdbc;

    JdbcOrderSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(PlacedOrder o) {
        int rows = jdbc.sql("""
                insert into order_snapshot (order_id, order_number, customer_id, currency, ship_to_country,
                                            ship_to_state, ship_to_postal_code, ship_to_city, placed_at)
                values (:id, :number, :customer, :currency, :country, :state, :postal, :city, :placedAt)
                on conflict do nothing
                """)
                .param("id", o.orderId()).param("number", o.orderNumber()).param("customer", o.customerId())
                .param("currency", o.currency()).param("country", o.shipTo().country())
                .param("state", o.shipTo().state()).param("postal", o.shipTo().postalCode())
                .param("city", o.shipTo().city()).param("placedAt", Sql.ts(o.placedAt()))
                .update();
        if (rows == 0) {
            return false;
        }
        int sp = 0;
        for (PlacedOrder.Shipment s : o.shipments()) {
            jdbc.sql("""
                    insert into shipment_snapshot (shipment_id, order_id, position, seller_org_id, seller_name,
                                                   fulfilment_type, delivery_method, total_minor, shipping_minor,
                                                   delivered_at)
                    values (:id, :order, :pos, :seller, :sellerName, :ff, :dm, :total, :shipping, :deliveredAt)
                    """)
                    .param("id", s.shipmentId()).param("order", o.orderId()).param("pos", sp++)
                    .param("seller", s.sellerOrgId()).param("sellerName", s.sellerName())
                    .param("ff", s.fulfilment().name()).param("dm", s.delivery().name())
                    .param("total", s.totalMinor()).param("shipping", s.shippingMinor())
                    .param("deliveredAt", Sql.ts(s.deliveredAt()))
                    .update();
            int lp = 0;
            for (PlacedOrder.Line l : s.lines()) {
                jdbc.sql("""
                        insert into order_line_snapshot (line_id, shipment_id, order_id, position, variant_atin, title,
                                                         quantity, unit_price_minor, tax_minor, product_type,
                                                         perishable)
                        values (:id, :shipment, :order, :pos, :atin, :title, :qty, :unit, :tax, :type, :perishable)
                        """)
                        .param("id", l.lineId()).param("shipment", s.shipmentId()).param("order", o.orderId())
                        .param("pos", lp++).param("atin", l.atin()).param("title", l.title())
                        .param("qty", l.quantity()).param("unit", l.unitPriceMinor()).param("tax", l.taxMinor())
                        .param("type", l.productType()).param("perishable", l.perishable())
                        .update();
            }
        }
        return true;
    }

    @Override
    public boolean markDelivered(UUID shipmentId, Instant deliveredAt) {
        return jdbc.sql("update shipment_snapshot set delivered_at = coalesce(delivered_at, :at) where shipment_id = :id")
                .param("at", Sql.ts(deliveredAt)).param("id", shipmentId).update() == 1;
    }

    @Override
    public Optional<PlacedOrder> find(UUID orderId) {
        return load(jdbc.sql("select order_id from order_snapshot where order_id = :id").param("id", orderId)
                .query((rs, n) -> rs.getObject("order_id", UUID.class)).optional());
    }

    @Override
    public Optional<PlacedOrder> findByNumber(String orderNumber) {
        return load(jdbc.sql("select order_id from order_snapshot where order_number = :n").param("n", orderNumber)
                .query((rs, n) -> rs.getObject("order_id", UUID.class)).optional());
    }

    private Optional<PlacedOrder> load(Optional<UUID> id) {
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, List<PlacedOrder.Line>> lines = new LinkedHashMap<>();
        jdbc.sql("select * from order_line_snapshot where order_id = :id order by position").param("id", id.get())
                .query(rs -> {
                    lines.computeIfAbsent(rs.getObject("shipment_id", UUID.class), k -> new ArrayList<>())
                            .add(new PlacedOrder.Line(rs.getString("line_id"), rs.getString("variant_atin"),
                                    rs.getString("title"), rs.getInt("quantity"), rs.getLong("unit_price_minor"),
                                    rs.getLong("tax_minor"), rs.getString("product_type"),
                                    rs.getBoolean("perishable")));
                });
        List<PlacedOrder.Shipment> shipments = jdbc.sql(
                        "select * from shipment_snapshot where order_id = :id order by position")
                .param("id", id.get())
                .query((rs, n) -> {
                    UUID sid = rs.getObject("shipment_id", UUID.class);
                    return new PlacedOrder.Shipment(sid, rs.getString("seller_org_id"), rs.getString("seller_name"),
                            FulfilmentType.valueOf(rs.getString("fulfilment_type")),
                            DeliveryMethod.valueOf(rs.getString("delivery_method")), rs.getLong("total_minor"),
                            rs.getLong("shipping_minor"), Sql.instant(rs, "delivered_at"),
                            lines.getOrDefault(sid, List.of()));
                }).list();
        return jdbc.sql("select * from order_snapshot where order_id = :id").param("id", id.get())
                .query((rs, n) -> new PlacedOrder(rs.getObject("order_id", UUID.class), rs.getString("order_number"),
                        rs.getString("customer_id"), rs.getString("currency"),
                        new TaxAddress(rs.getString("ship_to_country"), rs.getString("ship_to_state"),
                                rs.getString("ship_to_postal_code"), rs.getString("ship_to_city")),
                        Sql.instant(rs, "placed_at"), shipments)).optional();
    }
}
