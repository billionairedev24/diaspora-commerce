package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.DunningState;
import com.afrotransact.seller.domain.PaymentMethodKind;
import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.SubscriptionStatus;
import com.afrotransact.seller.domain.TrialSource;
import com.afrotransact.seller.port.SubscriptionRepository;
import com.afrotransact.seller.port.SubscriptionTerms;
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
                insert into subscription (org_id, plan_code, plan_version, cadence, status, payment_method_kind,
                                          updated_at, version)
                values (:org, :plan, :planVersion, :cadence, :status, :pm, :at, 0)
                on conflict (org_id) do nothing
                """)
                .param("org", s.orgId()).param("plan", s.planCode()).param("cadence", s.cadence().name())
                .param("planVersion", s.planVersion()).param("status", s.status().name())
                .param("pm", s.paymentMethodKind() == null ? null : s.paymentMethodKind().name())
                .param("at", Sql.ts(s.updatedAt()))
                .update() == 1;
    }

    @Override
    public Subscription update(Subscription s) {
        int rows = jdbc.sql("""
                update subscription set plan_code = :plan, plan_version = :planVersion, cadence = :cadence, status = :status,
                       payment_method_kind = :pm, anchor_date = :anchor, trial_ends_at = :trial, current_period_start = :start,
                       current_period_end = :end, cancel_at_period_end = :cancel, pending_plan_code = :pendingPlan,
                       pending_cadence = :pendingCadence, pending_effective_at = :pendingAt, dunning = :dunning,
                       failed_payment_attempts = :attempts, next_payment_attempt_at = :nextAttempt,
                       started_at = coalesce(started_at, coalesce(cast(:anchor as timestamptz), cast(:start as timestamptz))),
                       updated_at = :at, version = version + 1
                where org_id = :org and version = :version
                """)
                .param("plan", s.planCode()).param("planVersion", s.planVersion()).param("cadence", s.cadence().name())
                .param("status", s.status().name())
                .param("pm", s.paymentMethodKind() == null ? null : s.paymentMethodKind().name())
                .param("anchor", Sql.ts(s.anchorDate())).param("trial", Sql.ts(s.trialEndsAt()))
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
        return Optional.empty();
    }

    @Override
    public Optional<Subscription> findByCustomer(String customerId) {
        return Optional.empty();
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

    @Override
    public List<Subscription> dueRenewals(Instant now, int limit) {
        return jdbc.sql("""
                select * from subscription
                where status in ('ACTIVE', 'TRIALING', 'PAST_DUE')
                  and current_period_end is not null
                  and current_period_end <= :now
                order by current_period_end limit :limit
                """).param("now", Sql.ts(now)).param("limit", limit).query(this::map).list();
    }

    @Override
    public SubscriptionTerms terms(String orgId) {
        return jdbc.sql("select * from subscription where org_id = :o").param("o", orgId).query((rs, n) -> {
            String source = rs.getString("trial_source");
            String campaign = rs.getString("campaign_id");
            return new SubscriptionTerms(orgId, Sql.integer(rs, "trial_days_override"),
                    rs.getString("trial_override_reason"), rs.getString("trial_override_note"),
                    rs.getString("trial_override_by"), campaign == null ? null : UUID.fromString(campaign),
                    source == null ? null : TrialSource.valueOf(source), rs.getLong("credit_balance_cents"),
                    PlanFeeCollection.valueOf(rs.getString("plan_fee_collection")),
                    rs.getString("plan_fee_collection_source"), Sql.instant(rs, "plan_fee_collection_changed_at"),
                    rs.getString("plan_fee_collection_changed_by"));
        }).optional().orElse(SubscriptionTerms.none(orgId));
    }

    @Override
    public void setTrialOverride(String orgId, Integer days, String reason, String note, String actor) {
        jdbc.sql("update subscription set trial_days_override = :d, trial_override_reason = :r, "
                + "trial_override_note = :n, trial_override_by = :a where org_id = :o").param("d", days)
                .param("r", reason).param("n", note).param("a", actor).param("o", orgId).update();
    }

    @Override
    public void setTrialSource(String orgId, TrialSource source, UUID campaignId) {
        jdbc.sql("update subscription set trial_source = :s, campaign_id = :c where org_id = :o")
                .param("s", source == null ? null : source.name()).param("c", campaignId).param("o", orgId)
                .update();
    }

    @Override
    public void setCreditBalance(String orgId, long creditCents) {
        jdbc.sql("update subscription set credit_balance_cents = :c where org_id = :o").param("c", creditCents)
                .param("o", orgId).update();
    }

    @Override
    public long adjustCreditBalance(String orgId, long deltaCents) {
        return jdbc.sql("""
                update subscription set credit_balance_cents = greatest(0, credit_balance_cents + :delta)
                where org_id = :o
                returning credit_balance_cents
                """).param("delta", deltaCents).param("o", orgId).query(Long.class).single();
    }

    @Override
    public void setPlanFeeCollection(String orgId, PlanFeeCollection mode, String source, String actor,
                                     Instant at) {
        jdbc.sql("""
                update subscription set plan_fee_collection = :m, plan_fee_collection_source = :s,
                    plan_fee_collection_changed_at = :at, plan_fee_collection_changed_by = :a
                where org_id = :o
                """).param("m", mode.name()).param("s", source).param("at", Sql.ts(at)).param("a", actor)
                .param("o", orgId).update();
    }

    @Override
    public List<Subscription> onOtherVersions(String planCode, int version) {
        return jdbc.sql("select * from subscription where plan_code = :p and plan_version <> :v "
                + "and status <> 'CANCELED' order by org_id").param("p", planCode).param("v", version)
                .query(this::map).list();
    }

    @Override
    public List<Subscription> page(String afterOrgId, int limit) {
        return jdbc.sql("select * from subscription where org_id > :after order by org_id limit :limit")
                .param("after", afterOrgId == null ? "" : afterOrgId).param("limit", limit).query(this::map).list();
    }

    private Subscription map(ResultSet rs, int n) throws SQLException {
        String pm = rs.getString("payment_method_kind");
        String pendingCadence = rs.getString("pending_cadence");
        return new Subscription(rs.getString("org_id"), rs.getString("plan_code"), rs.getInt("plan_version"),
                Cadence.valueOf(rs.getString("cadence")), SubscriptionStatus.valueOf(rs.getString("status")),
                pm == null ? null : PaymentMethodKind.valueOf(pm),
                Sql.instant(rs, "anchor_date"),
                Sql.instant(rs, "trial_ends_at"), Sql.instant(rs, "current_period_start"),
                Sql.instant(rs, "current_period_end"), rs.getBoolean("cancel_at_period_end"),
                rs.getString("pending_plan_code"), pendingCadence == null ? null : Cadence.valueOf(pendingCadence),
                Sql.instant(rs, "pending_effective_at"), DunningState.valueOf(rs.getString("dunning")),
                rs.getInt("failed_payment_attempts"), Sql.instant(rs, "next_payment_attempt_at"),
                Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
