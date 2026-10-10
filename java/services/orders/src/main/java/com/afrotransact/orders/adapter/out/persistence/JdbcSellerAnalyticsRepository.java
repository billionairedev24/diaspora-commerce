package com.afrotransact.orders.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.orders.port.SellerAnalyticsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellerAnalyticsRepository implements SellerAnalyticsRepository {

    /** Shipments whose money counts as sales (not voided before payment, not cancelled, not returned). */
    private static final String SOLD = "s.status not in ('PENDING_PAYMENT', 'CANCELLED', 'RETURNED')";

    private final JdbcClient jdbc;

    JdbcSellerAnalyticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DayRow> days(String org, Instant from, Instant to, String zone) {
        return jdbc.sql("""
                select (o.created_at at time zone :zone)::date as day,
                       count(distinct o.id) as checkouts,
                       count(distinct o.id) filter (where o.placed_at is not null) as orders,
                       coalesce(sum(l.quantity * l.unit_price_minor - l.discount_minor)
                                filter (where o.placed_at is not null and %s), 0) as sales,
                       coalesce(sum(l.quantity) filter (where o.placed_at is not null and %s), 0) as units
                  from customer_order o
                  join shipment s on s.order_id = o.id and s.seller_org_id = :org
                  left join order_line l on l.shipment_id = s.id
                 where o.created_at >= :from and o.created_at < :to
                 group by 1 order by 1
                """.formatted(SOLD, SOLD))
                .param("zone", zone).param("org", org).param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new DayRow(rs.getObject("day", LocalDate.class), rs.getInt("checkouts"),
                        rs.getInt("orders"), rs.getLong("sales"), rs.getInt("units")))
                .list();
    }

    @Override
    public List<ProductRow> topProducts(String org, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                select coalesce(l.product_atin, l.variant_atin) as atin, max(l.title) as title,
                       sum(l.quantity * l.unit_price_minor - l.discount_minor) as revenue, sum(l.quantity) as units
                  from customer_order o
                  join shipment s on s.order_id = o.id and s.seller_org_id = :org
                  join order_line l on l.shipment_id = s.id
                 where o.created_at >= :from and o.created_at < :to and o.placed_at is not null and %s
                 group by 1 order by revenue desc, atin limit :limit
                """.formatted(SOLD))
                .param("org", org).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("limit", limit)
                .query((rs, n) -> new ProductRow(rs.getString("atin"), rs.getString("title"), rs.getLong("revenue"),
                        rs.getInt("units")))
                .list();
    }

    @Override
    public List<FulfilmentRow> byFulfilment(String org, Instant from, Instant to) {
        return jdbc.sql("""
                select s.fulfilment_type as type, count(distinct o.id) as orders
                  from customer_order o
                  join shipment s on s.order_id = o.id and s.seller_org_id = :org
                 where o.created_at >= :from and o.created_at < :to and o.placed_at is not null and %s
                 group by 1 order by 2 desc, 1
                """.formatted(SOLD))
                .param("org", org).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((rs, n) -> new FulfilmentRow(rs.getString("type"), rs.getInt("orders")))
                .list();
    }

    @Override
    public int toShip(String org) {
        return jdbc.sql("select count(*) from shipment where seller_org_id = :org and status in ('PLACED', "
                        + "'CONFIRMED', 'PACKED')")
                .param("org", org).query(Integer.class).single();
    }

    @Override
    public String currency(String org) {
        return jdbc.sql("select o.currency from customer_order o join shipment s on s.order_id = o.id "
                        + "where s.seller_org_id = :org order by o.created_at desc limit 1")
                .param("org", org).query(String.class).optional().orElse("USD");
    }
}
