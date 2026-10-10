package com.afrotransact.trustops.adapter.out.persistence;

import java.sql.Date;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.domain.SellerHealthMetrics;
import com.afrotransact.trustops.domain.SellerHealthReport;
import com.afrotransact.trustops.domain.SellerHealthStatus;
import com.afrotransact.trustops.port.SellerHealthRepository;
import com.afrotransact.trustops.port.ShipmentFact;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellerHealthRepository implements SellerHealthRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSellerHealthRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void apply(ShipmentFact f, Instant now) {
        jdbc.sql("""
                insert into seller_shipment (shipment_id, order_id, seller_org_id, placed_at, promised_by, delivered_at,
                    cancelled_at, cancelled_by, last_status, updated_at)
                values (:shipment, :order, :seller, :placedAt, :promisedBy, :deliveredAt, :cancelledAt, :cancelledBy,
                    :status, :now)
                on conflict (shipment_id) do update set
                    order_id = coalesce(excluded.order_id, seller_shipment.order_id),
                    placed_at = coalesce(seller_shipment.placed_at, excluded.placed_at),
                    promised_by = coalesce(excluded.promised_by, seller_shipment.promised_by),
                    delivered_at = coalesce(seller_shipment.delivered_at, excluded.delivered_at),
                    cancelled_at = coalesce(seller_shipment.cancelled_at, excluded.cancelled_at),
                    cancelled_by = coalesce(seller_shipment.cancelled_by, excluded.cancelled_by),
                    last_status = coalesce(excluded.last_status, seller_shipment.last_status),
                    updated_at = excluded.updated_at
                """)
                .param("shipment", f.shipmentId())
                .param("order", f.orderId())
                .param("seller", f.sellerOrgId())
                .param("placedAt", Sql.ts(f.placedAt()))
                .param("promisedBy", f.promisedBy() == null ? null : Date.valueOf(f.promisedBy()))
                .param("deliveredAt", Sql.ts(f.deliveredAt()))
                .param("cancelledAt", Sql.ts(f.cancelledAt()))
                .param("cancelledBy", f.cancelledBy())
                .param("status", f.status())
                .param("now", Sql.ts(now))
                .update();
    }

    /** Seller cancellations: by the seller, or by the system for it (not the customer's own, not staff's). */
    @Override
    public SellerHealthMetrics metrics(String orgId, Instant from, Instant to) {
        SellerHealthMetrics shipments = jdbc.sql("""
                select count(*) as shipments,
                       count(*) filter (where delivered_at is not null) as delivered,
                       count(*) filter (where delivered_at is not null and promised_by is not null
                                        and (delivered_at at time zone 'UTC')::date > promised_by) as late,
                       count(*) filter (where cancelled_at is not null
                                        and cancelled_by in ('SELLER', 'SYSTEM')) as cancelled
                from seller_shipment
                where seller_org_id = :org and placed_at >= :from and placed_at < :to
                """)
                .param("org", orgId).param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .query((rs, row) -> new SellerHealthMetrics(orgId, from, to, rs.getInt("shipments"),
                        rs.getInt("delivered"), rs.getInt("late"), rs.getInt("cancelled"), 0))
                .single();
        int claims = jdbc.sql("select count(*) from claim where seller_org_id = :org and opened_at >= :from "
                        + "and opened_at < :to")
                .param("org", orgId).param("from", Sql.ts(from)).param("to", Sql.ts(to)).query(Integer.class).single();
        return new SellerHealthMetrics(orgId, from, to, shipments.shipments(), shipments.delivered(),
                shipments.lateDeliveries(), shipments.sellerCancellations(), claims);
    }

    @Override
    public List<String> sellersActiveSince(Instant from) {
        return jdbc.sql("select distinct seller_org_id from seller_shipment where placed_at >= :from "
                        + "order by seller_org_id")
                .param("from", Sql.ts(from)).query(String.class).list();
    }

    @Override
    public void saveSnapshot(SellerHealthReport r, boolean pushedToOffers) {
        SellerHealthMetrics m = r.metrics();
        jdbc.sql("""
                insert into seller_health_snapshot (id, org_id, window_start, window_end, shipments, delivered,
                    late_deliveries, seller_cancellations, claims, status, breaches, computed_at, pushed_to_offers)
                values (:id, :org, :from, :to, :shipments, :delivered, :late, :cancelled, :claims, :status,
                    cast(:breaches as jsonb), :computedAt, :pushed)
                """)
                .param("id", UUID.randomUUID())
                .param("org", m.orgId())
                .param("from", Sql.ts(m.windowStart()))
                .param("to", Sql.ts(m.windowEnd()))
                .param("shipments", m.shipments())
                .param("delivered", m.delivered())
                .param("late", m.lateDeliveries())
                .param("cancelled", m.sellerCancellations())
                .param("claims", m.claims())
                .param("status", r.status().name())
                .param("breaches", json.write(r.breaches()))
                .param("computedAt", Sql.ts(r.computedAt()))
                .param("pushed", pushedToOffers)
                .update();
    }

    @Override
    public Optional<StoredHealth> latest(String orgId) {
        return jdbc.sql("select * from seller_health_snapshot where org_id = :org order by computed_at desc limit 1")
                .param("org", orgId)
                .query((rs, row) -> new StoredHealth(new SellerHealthReport(new SellerHealthMetrics(orgId,
                        Sql.instant(rs, "window_start"), Sql.instant(rs, "window_end"), rs.getInt("shipments"),
                        rs.getInt("delivered"), rs.getInt("late_deliveries"), rs.getInt("seller_cancellations"),
                        rs.getInt("claims")), SellerHealthStatus.valueOf(rs.getString("status")),
                        json.strings(rs.getString("breaches")), Sql.instant(rs, "computed_at")),
                        rs.getBoolean("pushed_to_offers")))
                .optional();
    }
}
