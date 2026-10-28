package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.BillingInterval;
import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipSource;
import com.afrotransact.payments.domain.MembershipStatus;
import com.afrotransact.payments.port.MembershipRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMembershipRepository implements MembershipRepository {

    private static final String SELECT = """
            select id, customer_id, tier_code, billing_interval, source, status, price_minor, currency, started_at,
                   current_period_start, current_period_end, trial_end, cancel_at_period_end, canceled_at, ended_at,
                   end_reason, pending_tier_code, pending_interval, payment_method_id, retry_count, next_retry_at,
                   free_days_granted, created_at, updated_at, version
            from membership
            """;
    private static final String LIVE = "status in ('TRIALING', 'ACTIVE', 'PAST_DUE')";

    private final JdbcClient jdbc;

    JdbcMembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Membership> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Membership> liveOf(String customerId) {
        return jdbc.sql(SELECT + " where customer_id = :c and " + LIVE).param("c", customerId).query(this::map)
                .optional();
    }

    @Override
    public Optional<Membership> lockLiveOf(String customerId) {
        return jdbc.sql(SELECT + " where customer_id = :c and " + LIVE + " for update").param("c", customerId)
                .query(this::map).optional();
    }

    @Override
    public Optional<Membership> lock(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public List<Membership> historyOf(String customerId, int limit) {
        return jdbc.sql(SELECT + " where customer_id = :c order by started_at desc, id limit :n")
                .param("c", customerId).param("n", limit).query(this::map).list();
    }

    @Override
    public void insert(Membership m) {
        jdbc.sql("""
                insert into membership (id, customer_id, tier_code, billing_interval, source, status, price_minor,
                    currency, started_at, current_period_start, current_period_end, trial_end, cancel_at_period_end,
                    canceled_at, ended_at, end_reason, pending_tier_code, pending_interval, payment_method_id,
                    retry_count, next_retry_at, free_days_granted, created_at, updated_at, version)
                values (:id, :customer, :tier, :interval, :source, :status, :price, :currency, :startedAt,
                    :periodStart, :periodEnd, :trialEnd, :cancelling, :canceledAt, :endedAt, :endReason, :pendingTier,
                    :pendingInterval, :method, :retries, :nextRetry, :freeDays, :createdAt, :updatedAt, 0)
                """)
                .params(params(m)).update();
    }

    @Override
    public Membership update(Membership m) {
        int updated = jdbc.sql("""
                update membership set tier_code = :tier, billing_interval = :interval, source = :source,
                    status = :status, price_minor = :price, currency = :currency, current_period_start = :periodStart,
                    current_period_end = :periodEnd, trial_end = :trialEnd, cancel_at_period_end = :cancelling,
                    canceled_at = :canceledAt, ended_at = :endedAt, end_reason = :endReason,
                    pending_tier_code = :pendingTier, pending_interval = :pendingInterval,
                    payment_method_id = :method, retry_count = :retries, next_retry_at = :nextRetry,
                    free_days_granted = :freeDays, updated_at = :updatedAt, version = version + 1
                where id = :id and version = :version
                """)
                .params(params(m)).param("version", m.version()).update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Membership " + m.id() + " was changed concurrently");
        }
        return find(m.id()).orElseThrow();
    }

    private static Map<String, Object> params(Membership m) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", m.id());
        p.put("customer", m.customerId());
        p.put("tier", m.tierCode());
        p.put("interval", m.interval().name());
        p.put("source", m.source().name());
        p.put("status", m.status().name());
        p.put("price", m.priceMinor());
        p.put("currency", m.currency());
        p.put("startedAt", Sql.ts(m.startedAt()));
        p.put("periodStart", Sql.ts(m.periodStart()));
        p.put("periodEnd", Sql.ts(m.periodEnd()));
        p.put("trialEnd", Sql.ts(m.trialEnd()));
        p.put("cancelling", m.cancelAtPeriodEnd());
        p.put("canceledAt", Sql.ts(m.canceledAt()));
        p.put("endedAt", Sql.ts(m.endedAt()));
        p.put("endReason", m.endReason());
        p.put("pendingTier", m.pendingTierCode());
        p.put("pendingInterval", m.pendingInterval() == null ? null : m.pendingInterval().name());
        p.put("method", m.paymentMethodId());
        p.put("retries", m.retryCount());
        p.put("nextRetry", Sql.ts(m.nextRetryAt()));
        p.put("freeDays", m.freeDaysGranted());
        p.put("createdAt", Sql.ts(m.createdAt()));
        p.put("updatedAt", Sql.ts(m.updatedAt()));
        return p;
    }

    @Override
    public List<UUID> dueIds(Instant now, int limit) {
        return jdbc.sql("""
                select id from membership
                where status in ('TRIALING', 'ACTIVE', 'PAST_DUE') and current_period_end <= :now
                  and (status <> 'PAST_DUE' or next_retry_at is null or next_retry_at <= :now)
                order by current_period_end, id limit :n
                """)
                .param("now", Sql.ts(now)).param("n", limit).query(UUID.class).list();
    }

    @Override
    public List<Membership> liveForMonthlyCredit(int limit, UUID after) {
        return jdbc.sql(SELECT + " where status = 'ACTIVE' and (cast(:after as uuid) is null or id > :after) "
                + "order by id limit :n")
                .param("after", after).param("n", limit).query(this::map).list();
    }

    @Override
    public List<Membership> list(String tierCode, MembershipStatus status, Instant before, int limit) {
        return jdbc.sql(SELECT + """
                 where (cast(:tier as varchar) is null or tier_code = :tier)
                   and (cast(:status as varchar) is null or status = :status)
                   and (cast(:before as timestamptz) is null or started_at < :before)
                 order by started_at desc, id limit :n
                """)
                .param("tier", tierCode).param("status", status == null ? null : status.name())
                .param("before", Sql.ts(before)).param("n", limit).query(this::map).list();
    }

    @Override
    public List<TierCount> countsByTier() {
        return jdbc.sql("""
                select tier_code, status, cancel_at_period_end, count(*) as n, coalesce(sum(price_minor), 0) as price,
                       coalesce(sum(case when billing_interval = 'YEARLY' then price_minor / 12 else price_minor end), 0)
                           as monthly
                from membership where status in ('TRIALING', 'ACTIVE', 'PAST_DUE')
                group by tier_code, status, cancel_at_period_end order by tier_code
                """)
                .query((rs, i) -> new TierCount(rs.getString("tier_code"),
                        MembershipStatus.valueOf(rs.getString("status")), rs.getBoolean("cancel_at_period_end"),
                        rs.getLong("n"), rs.getLong("price"), rs.getLong("monthly")))
                .list();
    }

    private Membership map(ResultSet rs, int n) throws SQLException {
        String pending = rs.getString("pending_interval");
        return new Membership(rs.getObject("id", UUID.class), rs.getString("customer_id"), rs.getString("tier_code"),
                BillingInterval.valueOf(rs.getString("billing_interval")),
                MembershipSource.valueOf(rs.getString("source")), MembershipStatus.valueOf(rs.getString("status")),
                rs.getLong("price_minor"), rs.getString("currency"), Sql.instant(rs, "started_at"),
                Sql.instant(rs, "current_period_start"), Sql.instant(rs, "current_period_end"),
                Sql.instant(rs, "trial_end"), rs.getBoolean("cancel_at_period_end"), Sql.instant(rs, "canceled_at"),
                Sql.instant(rs, "ended_at"), rs.getString("end_reason"), rs.getString("pending_tier_code"),
                pending == null ? null : BillingInterval.valueOf(pending), rs.getString("payment_method_id"),
                rs.getInt("retry_count"), Sql.instant(rs, "next_retry_at"), rs.getInt("free_days_granted"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
