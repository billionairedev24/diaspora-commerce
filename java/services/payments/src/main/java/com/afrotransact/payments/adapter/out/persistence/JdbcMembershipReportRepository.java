package com.afrotransact.payments.adapter.out.persistence;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.port.MembershipReportRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMembershipReportRepository implements MembershipReportRepository {

    private final JdbcClient jdbc;

    JdbcMembershipReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TierFlow> flowsByTier(Instant from, Instant to) {
        return jdbc.sql("""
                select tier_code,
                       count(*) filter (where started_at >= :from and started_at < :to) as joined,
                       count(*) filter (where ended_at >= :from and ended_at < :to and end_reason = 'CANCELED')
                           as canceled,
                       count(*) filter (where ended_at >= :from and ended_at < :to and end_reason = 'PAYMENT_FAILED')
                           as lapsed,
                       count(*) filter (where ended_at >= :from and ended_at < :to and end_reason = 'REFUNDED')
                           as refunded,
                       count(*) filter (where ended_at >= :from and ended_at < :to and end_reason = 'FREE_DAYS_OVER')
                           as expired
                from membership group by tier_code order by tier_code
                """)
                .param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .query((rs, i) -> new TierFlow(rs.getString("tier_code"), rs.getLong("joined"),
                        rs.getLong("canceled"), rs.getLong("lapsed"), rs.getLong("refunded"), rs.getLong("expired")))
                .list();
    }

    @Override
    public List<TierStart> liveAtStart(Instant at) {
        return jdbc.sql("""
                select tier_code, count(*) as members from membership
                where started_at < :at and (ended_at is null or ended_at >= :at)
                group by tier_code order by tier_code
                """)
                .param("at", Sql.ts(at))
                .query((rs, i) -> new TierStart(rs.getString("tier_code"), rs.getLong("members"))).list();
    }

    @Override
    public List<BenefitCost> benefitCosts(Instant from, Instant to) {
        return jdbc.sql("""
                select u.benefit, count(*) as uses, coalesce(sum(u.amount_minor), 0) as amount
                from membership_benefit_use u
                where u.created_at >= :from and u.created_at < :to
                  and not exists (select 1 from payment p where p.order_id = u.order_id
                                  and p.status in ('CANCELED', 'FAILED') and p.captured_minor = 0)
                group by u.benefit order by u.benefit
                """)
                .param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .query((rs, i) -> new BenefitCost(rs.getString("benefit"), rs.getLong("uses"), rs.getLong("amount")))
                .list();
    }

    @Override
    public List<TierRevenue> revenue(Instant from, Instant to) {
        return jdbc.sql("""
                select tier_code,
                       coalesce(sum(amount_minor) filter (where created_at >= :from and created_at < :to
                                                            and status in ('SUCCEEDED', 'REFUNDED')), 0) as charged,
                       coalesce(sum(refunded_minor) filter (where refunded_at >= :from and refunded_at < :to), 0)
                           as refunded,
                       count(*) filter (where created_at >= :from and created_at < :to
                                          and status in ('SUCCEEDED', 'REFUNDED')) as charges
                from membership_charge group by tier_code order by tier_code
                """)
                .param("from", Sql.ts(from)).param("to", Sql.ts(to))
                .query((rs, i) -> new TierRevenue(rs.getString("tier_code"), rs.getLong("charged"),
                        rs.getLong("refunded"), rs.getLong("charges")))
                .list();
    }
}
