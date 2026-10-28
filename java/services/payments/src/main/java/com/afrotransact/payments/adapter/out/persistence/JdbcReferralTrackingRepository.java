package com.afrotransact.payments.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.SignalKind;
import com.afrotransact.payments.port.ReferralTrackingRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReferralTrackingRepository implements ReferralTrackingRepository {

    private final JdbcClient jdbc;

    JdbcReferralTrackingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean trackOrder(UUID referralId, String orderId, Instant placedAt, long totalMinor,
                              List<Shipment> shipments) {
        int inserted = jdbc.sql("""
                insert into referral_order (order_id, referral_id, placed_at, total_minor)
                values (:order, :referral, :placed, :total) on conflict (order_id) do nothing
                """)
                .param("order", orderId).param("referral", referralId).param("placed", Sql.ts(placedAt))
                .param("total", totalMinor).update();
        if (inserted == 0) {
            return false;
        }
        for (Shipment s : shipments) {
            jdbc.sql("""
                    insert into referral_shipment (shipment_id, order_id, total_minor, state)
                    values (:shipment, :order, :total, 'OPEN') on conflict (shipment_id) do nothing
                    """)
                    .param("shipment", s.shipmentId()).param("order", orderId).param("total", s.totalMinor())
                    .update();
        }
        return true;
    }

    @Override
    public Optional<String> orderOfShipment(String shipmentId) {
        return jdbc.sql("select order_id from referral_shipment where shipment_id = :s").param("s", shipmentId)
                .query(String.class).optional();
    }

    @Override
    public void endShipment(String shipmentId, boolean delivered, Instant at) {
        // an ended shipment stays ended: a replayed or contradicting event changes nothing
        jdbc.sql("""
                update referral_shipment set state = :state, at = :at where shipment_id = :s and state = 'OPEN'
                """)
                .param("state", delivered ? "DELIVERED" : "CANCELLED").param("at", Sql.ts(at))
                .param("s", shipmentId).update();
    }

    @Override
    public Optional<OrderState> orderState(String orderId) {
        return jdbc.sql("""
                select o.referral_id,
                       count(*) filter (where s.state = 'OPEN') as open_count,
                       count(*) filter (where s.state = 'DELIVERED') as delivered_count,
                       coalesce(sum(s.total_minor) filter (where s.state = 'DELIVERED'), 0) as delivered_minor,
                       max(s.at) filter (where s.state = 'DELIVERED') as delivered_at
                from referral_order o join referral_shipment s on s.order_id = o.order_id
                where o.order_id = :order
                group by o.referral_id
                """).param("order", orderId)
                .query((rs, n) -> new OrderState(rs.getObject("referral_id", UUID.class), rs.getInt("open_count"),
                        rs.getInt("delivered_count"), rs.getLong("delivered_minor"), Sql.instant(rs, "delivered_at")))
                .optional();
    }

    @Override
    public void markQualified(String orderId) {
        jdbc.sql("update referral_order set qualified = true where order_id = :o").param("o", orderId).update();
    }

    @Override
    public void addSignal(String customerId, SignalKind kind, String hash, Instant now) {
        jdbc.sql("""
                insert into referral_signal (customer_id, kind, hash, seen_at) values (:c, :kind, :hash, :at)
                on conflict do nothing
                """)
                .param("c", customerId).param("kind", kind.name()).param("hash", hash).param("at", Sql.ts(now))
                .update();
    }

    @Override
    public boolean sharedSignal(String customerA, String customerB, SignalKind kind) {
        return jdbc.sql("""
                select exists (select 1 from referral_signal a join referral_signal b on a.kind = b.kind
                               and a.hash = b.hash
                               where a.customer_id = :a and b.customer_id = :b and a.kind = :kind)
                """).param("a", customerA).param("b", customerB).param("kind", kind.name())
                .query(Boolean.class).single();
    }

    @Override
    public boolean sharedPaymentMethod(String customerA, String customerB) {
        return jdbc.sql("""
                select exists (select 1 from customer_payment_method a join customer_payment_method b
                    on a.type = b.type and a.brand = b.brand and a.last4 = b.last4
                       and a.exp_month = b.exp_month and a.exp_year = b.exp_year
                    where a.customer_id = :a and b.customer_id = :b and a.type = 'card' and a.last4 is not null)
                """).param("a", customerA).param("b", customerB).query(Boolean.class).single();
    }

    @Override
    public long refundedMinor(String orderId) {
        return jdbc.sql("select coalesce(sum(refunded_minor), 0) from payment where order_id = :o")
                .param("o", orderId).query(Long.class).single();
    }
}
