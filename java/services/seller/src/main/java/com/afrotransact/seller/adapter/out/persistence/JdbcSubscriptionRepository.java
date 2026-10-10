package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.DunningState;
import com.afrotransact.seller.domain.PaymentMethodKind;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSubscriptionRepository implements SubscriptionRepository {

    private final JdbcClient jdbc;

    JdbcSubscriptionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(Subscription s) {
        return jdbc.sql("""
                insert into subscription (org_id, plan_code, cadence, status, payment_method_kind, updated_at, version)
                values (:org, :plan, :cadence, :status, :pm, :at, 0)
                on conflict (org_id) do nothing
                """)
                .param("org", s.orgId()).param("plan", s.planCode()).param("cadence", s.cadence().name())
                .param("status", s.status().name())
                .param("pm", s.paymentMethodKind() == null ? null : s.paymentMethodKind().name())
                .param("at", Sql.ts(s.updatedAt()))
                .update() == 1;
    }

    @Override
    public Subscription update(Subscription s) {
        int rows = jdbc.sql("""
                update subscription set plan_code = :plan, cadence = :cadence, status = :status,
                       payment_method_kind = :pm, stripe_customer_id = :customer, stripe_subscription_id = :sub,
                       stripe_item_id = :item, trial_ends_at = :trial, current_period_start = :start,
                       current_period_end = :end, cancel_at_period_end = :cancel, pending_plan_code = :pendingPlan,
                       pending_cadence = :pendingCadence, pending_effective_at = :pendingAt, dunning = :dunning,
                       failed_payment_attempts = :attempts, next_payment_attempt_at = :nextAttempt,
                       updated_at = :at, version = version + 1
                where org_id = :org and version = :version
                """)
                .param("plan", s.planCode()).param("cadence", s.cadence().name()).param("status", s.status().name())
                .param("pm", s.paymentMethodKind() == null ? null : s.paymentMethodKind().name())
                .param("customer", s.stripeCustomerId()).param("sub", s.stripeSubscriptionId())
                .param("item", s.stripeItemId()).param("trial", Sql.ts(s.trialEndsAt()))
                .param("start", Sql.ts(s.currentPeriodStart())).param("end", Sql.ts(s.currentPeriodEnd()))
                .param("cancel", s.cancelAtPeriodEnd()).param("pendingPlan", s.pendingPlanCode())
                .param("pendingCadence", s.pendingCadence() == null ? null : s.pendingCadence().name())
                .param("pendingAt", Sql.ts(s.pendingEffectiveAt())).param("dunning", s.dunning().name())
                .param("attempts", s.failedPaymentAttempts()).param("nextAttempt", Sql.ts(s.nextPaymentAttemptAt()))
                .param("at", Sql.ts(s.updatedAt())).param("org", s.orgId()).param("version", s.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "Subscription changed; reload and retry");
        }
        return find(s.orgId()).orElseThrow();
    }

    @Override
    public Optional<Subscription> find(String orgId) {
        return jdbc.sql("select * from subscription where org_id = :o").param("o", orgId).query(this::map)
                .optional();
    }

    @Override
    public Optional<Subscription> lock(String orgId) {
        return jdbc.sql("select * from subscription where org_id = :o for update").param("o", orgId)
                .query(this::map).optional();
    }

    @Override
    public Optional<Subscription> lockByStripeSubscription(String subscriptionId) {
        return jdbc.sql("select * from subscription where stripe_subscription_id = :s for update")
                .param("s", subscriptionId).query(this::map).optional();
    }

    @Override
    public Optional<Subscription> findByCustomer(String customerId) {
        return jdbc.sql("select * from subscription where stripe_customer_id = :c").param("c", customerId)
                .query(this::map).optional();
    }

    @Override
    public List<Subscription> pendingActivation(int limit) {
        return jdbc.sql("select * from subscription where status = 'PENDING_ACTIVATION' order by updated_at "
                + "limit :limit").param("limit", limit).query(this::map).list();
    }

    @Override
    public List<Subscription> dueChanges(Instant now, int limit) {
        return jdbc.sql("""
                select * from subscription where pending_plan_code is not null and pending_effective_at <= :now
                order by pending_effective_at limit :limit
                """).param("now", Sql.ts(now)).param("limit", limit).query(this::map).list();
    }

    private Subscription map(ResultSet rs, int n) throws SQLException {
        String pm = rs.getString("payment_method_kind");
        String pendingCadence = rs.getString("pending_cadence");
        return new Subscription(rs.getString("org_id"), rs.getString("plan_code"),
                Cadence.valueOf(rs.getString("cadence")), SubscriptionStatus.valueOf(rs.getString("status")),
                pm == null ? null : PaymentMethodKind.valueOf(pm), rs.getString("stripe_customer_id"),
                rs.getString("stripe_subscription_id"), rs.getString("stripe_item_id"),
                Sql.instant(rs, "trial_ends_at"), Sql.instant(rs, "current_period_start"),
                Sql.instant(rs, "current_period_end"), rs.getBoolean("cancel_at_period_end"),
                rs.getString("pending_plan_code"), pendingCadence == null ? null : Cadence.valueOf(pendingCadence),
                Sql.instant(rs, "pending_effective_at"), DunningState.valueOf(rs.getString("dunning")),
                rs.getInt("failed_payment_attempts"), Sql.instant(rs, "next_payment_attempt_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
