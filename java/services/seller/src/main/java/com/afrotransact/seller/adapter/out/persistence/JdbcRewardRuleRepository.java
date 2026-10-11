package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.RewardRule;
import com.afrotransact.seller.domain.RewardRuleStatus;
import com.afrotransact.seller.domain.RewardType;
import com.afrotransact.seller.port.RewardRuleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRewardRuleRepository implements RewardRuleRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcRewardRuleRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<RewardRule> list() {
        return jdbc.sql("select * from reward_rule order by created_at desc").query(this::map).list();
    }

    @Override
    public List<RewardRule> active(RewardMetric metric) {
        String where = metric == null ? "" : " and metric = :m";
        JdbcClient.StatementSpec spec = jdbc.sql("select * from reward_rule where status = 'ACTIVE'" + where
                + " order by created_at");
        if (metric != null) {
            spec = spec.param("m", metric.name());
        }
        return spec.query(this::map).list();
    }

    @Override
    public Optional<RewardRule> find(UUID id) {
        return jdbc.sql("select * from reward_rule where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public void insert(RewardRule r) {
        jdbc.sql("""
                insert into reward_rule (id, name, description, status, org_kind, metric, threshold, window_days,
                    within_days_of_approval, reward_type, free_days, credit_cents, percent_off, duration_invoices,
                    eligible_plans, starts_at, ends_at, max_grants, grants_count, created_by, created_at, updated_by,
                    updated_at, version)
                values (:id, :name, :description, :status, :kind, :metric, :threshold, :window, :within, :type,
                    :freeDays, :credit, :percent, :duration, cast(:plans as jsonb), :starts, :ends, :max, 0, :by, :at,
                    :by, :at, 0)
                """).params(params(r)).param("by", r.createdBy()).param("at", Sql.ts(r.createdAt())).update();
    }

    @Override
    public boolean update(RewardRule r) {
        return jdbc.sql("""
                update reward_rule set name = :name, description = :description, status = :status, org_kind = :kind,
                    metric = :metric, threshold = :threshold, window_days = :window, within_days_of_approval = :within,
                    reward_type = :type, free_days = :freeDays, credit_cents = :credit, percent_off = :percent,
                    duration_invoices = :duration, eligible_plans = cast(:plans as jsonb), starts_at = :starts,
                    ends_at = :ends, max_grants = :max, updated_by = :by, updated_at = :at, version = version + 1
                where id = :id and version = :version
                """).params(params(r)).param("by", r.updatedBy()).param("at", Sql.ts(r.updatedAt()))
                .param("version", r.version()).update() == 1;
    }

    @Override
    public boolean claimGrant(UUID ruleId) {
        return jdbc.sql("update reward_rule set grants_count = grants_count + 1 where id = :id "
                + "and (max_grants is null or grants_count < max_grants)").param("id", ruleId).update() == 1;
    }

    private Map<String, Object> params(RewardRule r) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("description", r.description());
        m.put("status", r.status().name());
        m.put("kind", r.kind() == null ? null : r.kind().name());
        m.put("metric", r.metric().name());
        m.put("threshold", r.threshold());
        m.put("window", r.windowDays());
        m.put("within", r.withinDaysOfApproval());
        m.put("type", r.rewardType().name());
        m.put("freeDays", r.freeDays());
        m.put("credit", r.creditCents());
        m.put("percent", r.percentOff());
        m.put("duration", r.durationInvoices());
        m.put("plans", json.write(r.eligiblePlans()));
        m.put("starts", Sql.ts(r.startsAt()));
        m.put("ends", Sql.ts(r.endsAt()));
        m.put("max", r.maxGrants());
        return m;
    }

    private RewardRule map(ResultSet rs, int n) throws SQLException {
        String kind = rs.getString("org_kind");
        long credit = rs.getLong("credit_cents");
        Long creditCents = rs.wasNull() ? null : credit;
        return new RewardRule(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                RewardRuleStatus.valueOf(rs.getString("status")), kind == null ? null : OrgKind.valueOf(kind),
                RewardMetric.valueOf(rs.getString("metric")), rs.getLong("threshold"), Sql.integer(rs, "window_days"),
                Sql.integer(rs, "within_days_of_approval"), RewardType.valueOf(rs.getString("reward_type")),
                Sql.integer(rs, "free_days"), creditCents, rs.getBigDecimal("percent_off"),
                Sql.integer(rs, "duration_invoices"), json.strings(rs.getString("eligible_plans")),
                Sql.instant(rs, "starts_at"), Sql.instant(rs, "ends_at"), Sql.integer(rs, "max_grants"),
                rs.getInt("grants_count"), rs.getString("created_by"), Sql.instant(rs, "created_at"),
                rs.getString("updated_by"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
