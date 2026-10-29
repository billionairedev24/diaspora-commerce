package com.afrotransact.seller.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.SellerFacts;
import com.afrotransact.seller.port.SellerMetricRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellerMetricRepository implements SellerMetricRepository {

    /**
     * Approved organisations with their subscription, plan, approval time (application.decided_at), subscription start
     * and next renewal (the trial end while trialing, else the period end).
     */
    private static final String FACTS = """
            select o.id, o.kind, s.plan_code, a.decided_at, s.started_at,
                   case when s.status = 'TRIALING' and s.trial_ends_at is not null then s.trial_ends_at
                        else s.current_period_end end as renewal_at,
                   (s.status in ('TRIALING', 'ACTIVE', 'PAST_DUE')
                    and exists (select 1 from plan_version_price pr where pr.plan_code = s.plan_code
                                and pr.version = s.plan_version and pr.amount_cents > 0)) as paid
            from organisation o
            join subscription s on s.org_id = o.id
            left join application a on a.org_id = o.id and a.status = 'APPROVED'
            where o.status = 'APPROVED'
            """;

    private final JdbcClient jdbc;

    JdbcSellerMetricRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(String orgId, RewardMetric metric, LocalDate day, long value) {
        jdbc.sql("""
                insert into seller_metric_daily (org_id, metric, day, value) values (:o, :m, :d, :v)
                on conflict (org_id, metric, day) do update set value = seller_metric_daily.value + excluded.value
                """).param("o", orgId).param("m", metric.name()).param("d", Date.valueOf(day)).param("v", value)
                .update();
    }

    @Override
    public long sum(String orgId, RewardMetric metric, LocalDate from, LocalDate to) {
        return jdbc.sql("select coalesce(sum(value), 0) from seller_metric_daily where org_id = :o and metric = :m "
                + "and day between :f and :t").param("o", orgId).param("m", metric.name())
                .param("f", Date.valueOf(from)).param("t", Date.valueOf(to)).query(Long.class).single();
    }

    @Override
    public Map<String, Long> sums(RewardMetric metric, LocalDate from, LocalDate to) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("select org_id, sum(value) as v from seller_metric_daily where metric = :m and day between :f and :t "
                + "group by org_id").param("m", metric.name()).param("f", Date.valueOf(from))
                .param("t", Date.valueOf(to)).query((ResultSet rs) -> {
                    while (rs.next()) {
                        out.put(rs.getString("org_id"), rs.getLong("v"));
                    }
                    return null;
                });
        return out;
    }

    @Override
    public boolean setListing(String offerId, String orgId, boolean active, Instant at) {
        return jdbc.sql("""
                insert into seller_listing_state (offer_id, org_id, active, updated_at) values (:id, :o, :a, :at)
                on conflict (offer_id) do update set org_id = excluded.org_id, active = excluded.active,
                    updated_at = excluded.updated_at
                where seller_listing_state.updated_at <= excluded.updated_at
                  and seller_listing_state.active <> excluded.active
                """).param("id", offerId).param("o", orgId).param("a", active).param("at", Sql.ts(at)).update() == 1;
    }

    @Override
    public long activeListings(String orgId) {
        return jdbc.sql("select count(*) from seller_listing_state where org_id = :o and active").param("o", orgId)
                .query(Long.class).single();
    }

    @Override
    public Map<String, Long> activeListingsByOrg() {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("select org_id, count(*) as n from seller_listing_state where active group by org_id")
                .query((ResultSet rs) -> {
                    while (rs.next()) {
                        out.put(rs.getString("org_id"), rs.getLong("n"));
                    }
                    return null;
                });
        return out;
    }

    @Override
    public Optional<SellerFacts> facts(String orgId) {
        return jdbc.sql(FACTS + " and o.id = :o").param("o", orgId).query(this::mapFacts).optional();
    }

    @Override
    public List<SellerFacts> allFacts() {
        return jdbc.sql(FACTS + " order by o.id").query(this::mapFacts).list();
    }

    private SellerFacts mapFacts(ResultSet rs, int n) throws SQLException {
        return new SellerFacts(rs.getString("id"), OrgKind.valueOf(rs.getString("kind")), rs.getString("plan_code"),
                Sql.instant(rs, "decided_at"), rs.getBoolean("paid"), Sql.instant(rs, "started_at"),
                Sql.instant(rs, "renewal_at"));
    }
}
