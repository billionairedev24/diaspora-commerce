package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.GrantSource;
import com.afrotransact.seller.domain.GrantStatus;
import com.afrotransact.seller.domain.GrantType;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcBillingGrantRepository implements BillingGrantRepository {

    private final JdbcClient jdbc;

    JdbcBillingGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BillingGrant insertOnce(BillingGrant g) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", g.id());
        p.put("org", g.orgId());
        p.put("source", g.source().name());
        p.put("rule", g.ruleId());
        p.put("key", g.requestKey());
        p.put("type", g.type().name());
        p.put("credit", g.creditCents());
        p.put("days", g.freeDays());
        p.put("percent", g.percentOff());
        p.put("duration", g.durationInvoices());
        p.put("invoice", g.invoiceId());
        p.put("trialEnd", Sql.ts(g.trialEnd()));
        p.put("status", g.status().name());
        p.put("reason", g.reasonCode());
        p.put("note", g.note());
        p.put("summary", g.summary());
        p.put("actor", g.actorId());
        p.put("metric", g.metric() == null ? null : g.metric().name());
        p.put("metricValue", g.metricValue());
        p.put("at", Sql.ts(g.createdAt()));
        int rows = jdbc.sql("""
                insert into billing_grant (id, org_id, source, rule_id, request_key, type, credit_cents, free_days,
                    percent_off, duration_invoices, invoice_id, trial_end, status, reason_code, note, summary, actor_id,
                    metric, metric_value, created_at)
                values (:id, :org, :source, :rule, :key, :type, :credit, :days, :percent, :duration, :invoice,
                    :trialEnd, :status, :reason, :note, :summary, :actor, :metric, :metricValue, :at)
                on conflict do nothing
                """).params(p).update();
        if (rows == 1) {
            return find(g.id()).orElseThrow();
        }
        String sql = g.ruleId() != null ? "select * from billing_grant where rule_id = :r and org_id = :o"
                : "select * from billing_grant where org_id = :o and request_key = :k";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("o", g.orgId());
        spec = g.ruleId() != null ? spec.param("r", g.ruleId()) : spec.param("k", g.requestKey());
        return spec.query(this::map).single();
    }

    @Override
    public Optional<BillingGrant> find(UUID id) {
        return jdbc.sql("select * from billing_grant where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public BillingGrant update(BillingGrant g) {
        BillingGrant.StripeRefs s = g.stripe();
        int rows = jdbc.sql("""
                update billing_grant set status = :status, stripe_customer_id = :customer,
                    stripe_subscription_id = :sub, stripe_balance_transaction_id = :txn, stripe_coupon_id = :coupon,
                    previous_trial_end = :prev, new_trial_end = :next, attempts = :attempts, last_error = :error,
                    applied_at = :applied, version = version + 1
                where id = :id and version = :version
                """).param("status", g.status().name()).param("customer", s.customerId())
                .param("sub", s.subscriptionId()).param("txn", s.balanceTransactionId()).param("coupon", s.couponId())
                .param("prev", Sql.ts(s.previousTrialEnd())).param("next", Sql.ts(s.newTrialEnd()))
                .param("attempts", g.attempts()).param("error", g.lastError()).param("applied", Sql.ts(g.appliedAt()))
                .param("id", g.id()).param("version", g.version()).update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "The grant changed; reload and retry");
        }
        return find(g.id()).orElseThrow();
    }

    @Override
    public List<BillingGrant> forOrg(String orgId, int limit) {
        return jdbc.sql("select * from billing_grant where org_id = :o order by created_at desc limit :l")
                .param("o", orgId).param("l", limit).query(this::map).list();
    }

    @Override
    public Page<BillingGrant> search(GrantFilter f, int page, int size) {
        List<String> where = new ArrayList<>();
        Map<String, Object> p = new HashMap<>();
        if (f.orgId() != null) {
            where.add("org_id = :org");
            p.put("org", f.orgId());
        }
        if (f.source() != null) {
            where.add("source = :source");
            p.put("source", f.source().name());
        }
        if (f.type() != null) {
            where.add("type = :type");
            p.put("type", f.type().name());
        }
        if (f.ruleId() != null) {
            where.add("rule_id = :rule");
            p.put("rule", f.ruleId());
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.add("(org_id ilike :q or summary ilike :q or actor_id ilike :q)");
            p.put("q", "%" + f.q().trim().replace("%", "\\%") + "%");
        }
        String w = where.isEmpty() ? "" : " where " + String.join(" and ", where);
        long total = jdbc.sql("select count(*) from billing_grant" + w).params(p).query(Long.class).single();
        p.put("limit", size);
        p.put("offset", (long) page * size);
        List<BillingGrant> items = jdbc.sql("select * from billing_grant" + w
                + " order by created_at desc, id limit :limit offset :offset").params(p).query(this::map).list();
        return new Page<>(items, page, size, total);
    }

    @Override
    public Set<UUID> grantedRules(String orgId) {
        return new HashSet<>(jdbc.sql("select rule_id from billing_grant where org_id = :o and rule_id is not null")
                .param("o", orgId).query((rs, n) -> rs.getObject("rule_id", UUID.class)).list());
    }

    @Override
    public Set<String> grantedOrgs(UUID ruleId) {
        return new HashSet<>(jdbc.sql("select org_id from billing_grant where rule_id = :r").param("r", ruleId)
                .query(String.class).list());
    }

    @Override
    public List<BillingGrant> retryable(Instant before, int maxAttempts, int limit) {
        return jdbc.sql("""
                select * from billing_grant where status in ('PENDING', 'FAILED') and attempts < :max
                and created_at < :before order by created_at limit :limit
                """).param("max", maxAttempts).param("before", Sql.ts(before)).param("limit", limit)
                .query(this::map).list();
    }

    @Override
    public List<ReasonCode> reasonCodes() {
        return jdbc.sql("select code, label from billing_reason_code order by sort_order")
                .query((rs, n) -> new ReasonCode(rs.getString("code"), rs.getString("label"))).list();
    }

    private BillingGrant map(ResultSet rs, int n) throws SQLException {
        String metric = rs.getString("metric");
        long credit = rs.getLong("credit_cents");
        Long creditCents = rs.wasNull() ? null : credit;
        long mv = rs.getLong("metric_value");
        Long metricValue = rs.wasNull() ? null : mv;
        return new BillingGrant(rs.getObject("id", UUID.class), rs.getString("org_id"),
                GrantSource.valueOf(rs.getString("source")), rs.getObject("rule_id", UUID.class),
                rs.getString("request_key"), GrantType.valueOf(rs.getString("type")), creditCents,
                Sql.integer(rs, "free_days"), rs.getBigDecimal("percent_off"), Sql.integer(rs, "duration_invoices"),
                rs.getString("invoice_id"), Sql.instant(rs, "trial_end"), GrantStatus.valueOf(rs.getString("status")),
                rs.getString("reason_code"), rs.getString("note"), rs.getString("summary"), rs.getString("actor_id"),
                metric == null ? null : RewardMetric.valueOf(metric), metricValue,
                new BillingGrant.StripeRefs(rs.getString("stripe_customer_id"), rs.getString("stripe_subscription_id"),
                        rs.getString("stripe_balance_transaction_id"), rs.getString("stripe_coupon_id"),
                        Sql.instant(rs, "previous_trial_end"), Sql.instant(rs, "new_trial_end")),
                rs.getInt("attempts"), rs.getString("last_error"), Sql.instant(rs, "created_at"),
                Sql.instant(rs, "applied_at"), rs.getLong("version"));
    }
}
