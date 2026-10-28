package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.TierBenefit;
import com.afrotransact.payments.domain.TierStatus;
import com.afrotransact.payments.port.MembershipTierRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcMembershipTierRepository implements MembershipTierRepository {

    private static final TypeReference<List<TierBenefit>> BENEFITS = new TypeReference<>() { };
    private static final String SELECT = """
            select code, name, description, status, sort_order, currency, monthly_price_minor, yearly_price_minor,
                   trial_days, benefits::text as benefits, created_at, updated_by, updated_at, version
            from membership_tier
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcMembershipTierRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<MembershipTier> find(String code) {
        return jdbc.sql(SELECT + " where code = :code").param("code", code).query(this::map).optional();
    }

    @Override
    public Optional<MembershipTier> lock(String code) {
        return jdbc.sql(SELECT + " where code = :code for update").param("code", code).query(this::map).optional();
    }

    @Override
    public List<MembershipTier> all() {
        return jdbc.sql(SELECT + " order by sort_order, code").query(this::map).list();
    }

    @Override
    public List<MembershipTier> published() {
        return jdbc.sql(SELECT + " where status = 'PUBLISHED' order by sort_order, code").query(this::map).list();
    }

    @Override
    public void insert(MembershipTier t) {
        jdbc.sql("""
                insert into membership_tier (code, name, description, status, sort_order, currency,
                    monthly_price_minor, yearly_price_minor, trial_days, benefits, created_at, updated_by, updated_at)
                values (:code, :name, :description, :status, :sort, :currency, :monthly, :yearly, :trial,
                    cast(:benefits as jsonb), :createdAt, :by, :at)
                """)
                .param("code", t.code()).param("name", t.name()).param("description", t.description())
                .param("status", t.status().name()).param("sort", t.sortOrder()).param("currency", t.currency())
                .param("monthly", t.monthlyPriceMinor()).param("yearly", t.yearlyPriceMinor())
                .param("trial", t.trialDays()).param("benefits", json.write(t.benefits()))
                .param("createdAt", Sql.ts(t.createdAt())).param("by", t.updatedBy()).param("at", Sql.ts(t.updatedAt()))
                .update();
    }

    @Override
    public MembershipTier update(MembershipTier t, long expectedVersion) {
        int updated = jdbc.sql("""
                update membership_tier set name = :name, description = :description, status = :status,
                    sort_order = :sort, monthly_price_minor = :monthly, yearly_price_minor = :yearly,
                    trial_days = :trial, benefits = cast(:benefits as jsonb), updated_by = :by, updated_at = :at,
                    version = version + 1
                where code = :code and version = :version
                """)
                .param("name", t.name()).param("description", t.description()).param("status", t.status().name())
                .param("sort", t.sortOrder()).param("monthly", t.monthlyPriceMinor())
                .param("yearly", t.yearlyPriceMinor()).param("trial", t.trialDays())
                .param("benefits", json.write(t.benefits())).param("by", t.updatedBy())
                .param("at", Sql.ts(t.updatedAt())).param("code", t.code()).param("version", expectedVersion)
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Membership tier " + t.code() + " was changed concurrently");
        }
        return find(t.code()).orElseThrow();
    }

    @Override
    public void addChange(Change c) {
        jdbc.sql("""
                insert into membership_tier_change (id, tier_code, action, changed_by, changed_at, reason, before,
                    after)
                values (:id, :tier, :action, :by, :at, :reason, cast(:before as jsonb), cast(:after as jsonb))
                """)
                .param("id", c.id()).param("tier", c.tierCode()).param("action", c.action())
                .param("by", c.changedBy()).param("at", Sql.ts(c.changedAt())).param("reason", c.reason())
                .param("before", c.beforeJson()).param("after", c.afterJson()).update();
    }

    @Override
    public List<Change> changes(String tierCode, int limit) {
        return jdbc.sql("""
                select id, tier_code, action, changed_by, changed_at, reason, before::text as before_json,
                       after::text as after_json
                from membership_tier_change where tier_code = :tier order by changed_at desc, id limit :n
                """)
                .param("tier", tierCode).param("n", limit)
                .query((rs, n) -> new Change(rs.getObject("id", UUID.class), rs.getString("tier_code"),
                        rs.getString("action"), rs.getString("changed_by"), Sql.instant(rs, "changed_at"),
                        rs.getString("reason"), rs.getString("before_json"), rs.getString("after_json")))
                .list();
    }

    private MembershipTier map(ResultSet rs, int n) throws SQLException {
        return new MembershipTier(rs.getString("code"), rs.getString("name"), rs.getString("description"),
                TierStatus.valueOf(rs.getString("status")), rs.getInt("sort_order"), rs.getString("currency"),
                rs.getLong("monthly_price_minor"), rs.getLong("yearly_price_minor"), rs.getInt("trial_days"),
                json.read(rs.getString("benefits"), BENEFITS), Sql.instant(rs, "created_at"),
                rs.getString("updated_by"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
