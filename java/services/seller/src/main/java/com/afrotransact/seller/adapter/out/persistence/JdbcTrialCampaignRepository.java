package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.CampaignStatus;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.TrialCampaign;
import com.afrotransact.seller.port.TrialCampaignRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTrialCampaignRepository implements TrialCampaignRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcTrialCampaignRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<TrialCampaign> list() {
        return jdbc.sql("select * from trial_campaign order by created_at desc").query(this::map).list();
    }

    @Override
    public List<TrialCampaign> active() {
        return jdbc.sql("select * from trial_campaign where status = 'ACTIVE' order by trial_days desc")
                .query(this::map).list();
    }

    @Override
    public Optional<TrialCampaign> find(UUID id) {
        return jdbc.sql("select * from trial_campaign where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<TrialCampaign> byPromoCode(String code) {
        String c = TrialCampaign.normalise(code);
        if (c == null) {
            return Optional.empty();
        }
        return jdbc.sql("select * from trial_campaign where promo_code = :c").param("c", c).query(this::map)
                .optional();
    }

    @Override
    public void insert(TrialCampaign c) {
        jdbc.sql("""
                insert into trial_campaign (id, name, promo_code, trial_days, signup_from, signup_to, org_kind,
                    eligible_plans, max_redemptions, redemptions, status, created_by, created_at, updated_by,
                    updated_at, version)
                values (:id, :name, :code, :days, :from, :to, :kind, cast(:plans as jsonb), :max, 0, :status, :by, :at,
                        :by, :at, 0)
                """).param("id", c.id()).param("name", c.name()).param("code", c.promoCode())
                .param("days", c.trialDays()).param("from", Sql.ts(c.signupFrom())).param("to", Sql.ts(c.signupTo()))
                .param("kind", c.kind() == null ? null : c.kind().name()).param("plans", json.write(c.eligiblePlans()))
                .param("max", c.maxRedemptions()).param("status", c.status().name()).param("by", c.createdBy())
                .param("at", Sql.ts(c.createdAt())).update();
    }

    @Override
    public boolean update(TrialCampaign c) {
        return jdbc.sql("""
                update trial_campaign set name = :name, promo_code = :code, trial_days = :days, signup_from = :from,
                    signup_to = :to, org_kind = :kind, eligible_plans = cast(:plans as jsonb), max_redemptions = :max,
                    status = :status, updated_by = :by, updated_at = :at, version = version + 1
                where id = :id and version = :version
                """).param("id", c.id()).param("name", c.name()).param("code", c.promoCode())
                .param("days", c.trialDays()).param("from", Sql.ts(c.signupFrom())).param("to", Sql.ts(c.signupTo()))
                .param("kind", c.kind() == null ? null : c.kind().name()).param("plans", json.write(c.eligiblePlans()))
                .param("max", c.maxRedemptions()).param("status", c.status().name()).param("by", c.updatedBy())
                .param("at", Sql.ts(c.updatedAt())).param("version", c.version()).update() == 1;
    }

    @Override
    public boolean redeem(UUID id) {
        return jdbc.sql("update trial_campaign set redemptions = redemptions + 1 where id = :id "
                + "and (max_redemptions is null or redemptions < max_redemptions)").param("id", id).update() == 1;
    }

    private TrialCampaign map(ResultSet rs, int n) throws SQLException {
        String kind = rs.getString("org_kind");
        return new TrialCampaign(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("promo_code"),
                rs.getInt("trial_days"), Sql.instant(rs, "signup_from"), Sql.instant(rs, "signup_to"),
                kind == null ? null : OrgKind.valueOf(kind), json.strings(rs.getString("eligible_plans")),
                Sql.integer(rs, "max_redemptions"), rs.getInt("redemptions"),
                CampaignStatus.valueOf(rs.getString("status")), rs.getString("created_by"),
                Sql.instant(rs, "created_at"), rs.getString("updated_by"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
