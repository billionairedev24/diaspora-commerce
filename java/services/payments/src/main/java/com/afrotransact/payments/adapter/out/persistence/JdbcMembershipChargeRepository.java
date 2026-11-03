package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.MembershipCharge;
import com.afrotransact.payments.port.MembershipChargeRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMembershipChargeRepository implements MembershipChargeRepository {

    private static final String SELECT = """
            select id, membership_id, customer_id, tier_code, kind, attempt, amount_minor, currency, status,
                   stripe_payment_intent_id, stripe_charge_id, stripe_refund_id, failure_code, failure_message,
                   period_start, period_end, idempotency_key, refunded_minor, refund_reason, refunded_by,
                   refunded_at, created_at
            from membership_charge
            """;

    private final JdbcClient jdbc;

    JdbcMembershipChargeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(MembershipCharge c) {
        jdbc.sql("""
                insert into membership_charge (id, membership_id, customer_id, tier_code, kind, attempt, amount_minor,
                    currency, status, stripe_payment_intent_id, stripe_charge_id, stripe_refund_id, failure_code,
                    failure_message, period_start, period_end, idempotency_key, refunded_minor, refund_reason,
                    refunded_by, refunded_at, created_at)
                values (:id, :membership, :customer, :tier, :kind, :attempt, :amount, :currency, :status, :intent,
                    :charge, :refund, :failureCode, :failureMessage, :periodStart, :periodEnd, :key, :refunded,
                    :refundReason, :refundedBy, :refundedAt, :createdAt)
                """)
                .param("id", c.id()).param("membership", c.membershipId()).param("customer", c.customerId())
                .param("tier", c.tierCode()).param("kind", c.kind().name()).param("attempt", c.attempt())
                .param("amount", c.amount().minor()).param("currency", c.amount().currency())
                .param("status", c.status().name()).param("intent", c.paymentIntentId())
                .param("charge", c.chargeId()).param("refund", c.refundId()).param("failureCode", c.failureCode())
                .param("failureMessage", c.failureMessage()).param("periodStart", Sql.ts(c.periodStart()))
                .param("periodEnd", Sql.ts(c.periodEnd())).param("key", c.idempotencyKey())
                .param("refunded", c.refundedMinor()).param("refundReason", c.refundReason())
                .param("refundedBy", c.refundedBy()).param("refundedAt", Sql.ts(c.refundedAt()))
                .param("createdAt", Sql.ts(c.createdAt())).update();
    }

    @Override
    public MembershipCharge update(MembershipCharge c) {
        jdbc.sql("""
                update membership_charge set status = :status, stripe_refund_id = :refund, refunded_minor = :refunded,
                    refund_reason = :reason, refunded_by = :by, refunded_at = :at
                where id = :id
                """)
                .param("status", c.status().name()).param("refund", c.refundId()).param("refunded", c.refundedMinor())
                .param("reason", c.refundReason()).param("by", c.refundedBy()).param("at", Sql.ts(c.refundedAt()))
                .param("id", c.id()).update();
        return find(c.id()).orElseThrow();
    }

    @Override
    public Optional<MembershipCharge> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<MembershipCharge> lock(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<MembershipCharge> findByKey(String key) {
        return jdbc.sql(SELECT + " where idempotency_key = :key").param("key", key).query(this::map).optional();
    }

    @Override
    public List<MembershipCharge> ofMembership(UUID membershipId, int limit) {
        return jdbc.sql(SELECT + " where membership_id = :m order by created_at desc, id limit :n")
                .param("m", membershipId).param("n", limit).query(this::map).list();
    }

    @Override
    public List<MembershipCharge> ofCustomer(String customerId, int limit) {
        return jdbc.sql(SELECT + " where customer_id = :c order by created_at desc, id limit :n")
                .param("c", customerId).param("n", limit).query(this::map).list();
    }

    @Override
    public Optional<MembershipCharge> latestPaid(UUID membershipId) {
        return jdbc.sql(SELECT + " where membership_id = :m and status = 'SUCCEEDED' "
                + "order by created_at desc, id limit 1").param("m", membershipId).query(this::map).optional();
    }

    private MembershipCharge map(ResultSet rs, int n) throws SQLException {
        return new MembershipCharge(rs.getObject("id", UUID.class), rs.getObject("membership_id", UUID.class),
                rs.getString("customer_id"), rs.getString("tier_code"),
                MembershipCharge.Kind.valueOf(rs.getString("kind")), rs.getInt("attempt"),
                Money.of(rs.getLong("amount_minor"), rs.getString("currency")),
                MembershipCharge.Status.valueOf(rs.getString("status")), rs.getString("stripe_payment_intent_id"),
                rs.getString("stripe_charge_id"), rs.getString("stripe_refund_id"), rs.getString("failure_code"),
                rs.getString("failure_message"), Sql.instant(rs, "period_start"), Sql.instant(rs, "period_end"),
                rs.getString("idempotency_key"), rs.getLong("refunded_minor"), rs.getString("refund_reason"),
                rs.getString("refunded_by"), Sql.instant(rs, "refunded_at"), Sql.instant(rs, "created_at"));
    }
}
