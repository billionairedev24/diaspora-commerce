package com.afrotransact.orders.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.port.OrderCouponRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrderCouponRepository implements OrderCouponRepository {

    private final JdbcClient jdbc;

    JdbcOrderCouponRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(OrderCoupon c) {
        jdbc.sql("""
                insert into order_coupon (order_id, code, redemption_id, discount_minor, shipping_discount_minor,
                                          funding, funded_by_org, status, created_at)
                values (:order, :code, :redemption, :discount, :shipping, :funding, :org, :status, :at)
                on conflict (order_id) do nothing
                """)
                .param("order", UUID.fromString(c.orderId())).param("code", c.code())
                .param("redemption", c.redemptionId()).param("discount", c.discountMinor())
                .param("shipping", c.shippingDiscountMinor()).param("funding", c.funding())
                .param("org", c.fundedByOrg()).param("status", c.status())
                .param("at", Timestamp.from(c.createdAt()))
                .update();
    }

    @Override
    public Optional<OrderCoupon> find(String orderId) {
        return jdbc.sql("select * from order_coupon where order_id = :o").param("o", UUID.fromString(orderId))
                .query(JdbcOrderCouponRepository::map).optional();
    }

    @Override
    public boolean markReleased(String orderId, Instant at) {
        return jdbc.sql("update order_coupon set status = 'RELEASED', released_at = :at where order_id = :o and "
                        + "status = 'REDEEMED'").param("o", UUID.fromString(orderId)).param("at", Timestamp.from(at))
                .update() == 1;
    }

    private static OrderCoupon map(ResultSet rs, int n) throws SQLException {
        Timestamp released = rs.getTimestamp("released_at");
        return new OrderCoupon(rs.getObject("order_id", UUID.class).toString(), rs.getString("code"),
                rs.getString("redemption_id"), rs.getLong("discount_minor"), rs.getLong("shipping_discount_minor"),
                rs.getString("funding"), rs.getString("funded_by_org"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), released == null ? null : released.toInstant());
    }
}
