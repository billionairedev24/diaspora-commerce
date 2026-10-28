package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.ReferralReward;
import com.afrotransact.payments.domain.ReferralSettings;
import com.afrotransact.payments.domain.RewardType;
import com.afrotransact.payments.port.ReferralSettingsRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReferralSettingsRepository implements ReferralSettingsRepository {

    private static final String SELECT = """
            select enabled, referrer_reward_type, referrer_credit_minor, referrer_membership_days,
                   referred_reward_type, referred_credit_minor, referred_membership_days, qualifying_event,
                   min_order_minor, return_window_days, max_rewarded_per_referrer, max_credit_per_customer_minor,
                   validity_days, updated_by, updated_at, version
            from referral_settings where id = 1
            """;

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcReferralSettingsRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public ReferralSettings get() {
        return jdbc.sql(SELECT).query(this::map).single();
    }

    @Override
    public ReferralSettings lock() {
        return jdbc.sql(SELECT + " for update").query(this::map).single();
    }

    @Override
    public ReferralSettings save(ReferralSettings next, UUID changeId, String reason, ReferralSettings before) {
        int updated = jdbc.sql("""
                update referral_settings set enabled = :enabled,
                    referrer_reward_type = :rType, referrer_credit_minor = :rCredit, referrer_membership_days = :rDays,
                    referred_reward_type = :dType, referred_credit_minor = :dCredit, referred_membership_days = :dDays,
                    qualifying_event = :event, min_order_minor = :min, return_window_days = :window,
                    max_rewarded_per_referrer = :maxRewarded, max_credit_per_customer_minor = :maxCredit,
                    validity_days = :validity, updated_by = :by, updated_at = :at, version = version + 1
                where id = 1 and version = :version
                """)
                .param("enabled", next.enabled())
                .param("rType", next.referrer().type().name()).param("rCredit", next.referrer().creditMinor())
                .param("rDays", next.referrer().membershipDays())
                .param("dType", next.referred().type().name()).param("dCredit", next.referred().creditMinor())
                .param("dDays", next.referred().membershipDays())
                .param("event", next.qualifyingEvent().name()).param("min", next.minOrderMinor())
                .param("window", next.returnWindowDays()).param("maxRewarded", next.maxRewardedPerReferrer())
                .param("maxCredit", next.maxCreditPerCustomerMinor()).param("validity", next.validityDays())
                .param("by", next.updatedBy()).param("at", Sql.ts(next.updatedAt()))
                .param("version", before.version()).update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Referral settings were changed concurrently");
        }
        jdbc.sql("""
                insert into referral_settings_change (id, changed_by, changed_at, reason, before, after)
                values (:id, :by, :at, :reason, cast(:before as jsonb), cast(:after as jsonb))
                """)
                .param("id", changeId).param("by", next.updatedBy()).param("at", Sql.ts(next.updatedAt()))
                .param("reason", reason).param("before", json.write(before)).param("after", json.write(next))
                .update();
        return get();
    }

    @Override
    public List<Change> changes(int limit) {
        return jdbc.sql("""
                select id, changed_by, changed_at, reason, before::text as before_json, after::text as after_json
                from referral_settings_change order by changed_at desc limit :n
                """)
                .param("n", limit)
                .query((rs, n) -> new Change(rs.getObject("id", UUID.class), rs.getString("changed_by"),
                        Sql.instant(rs, "changed_at"), rs.getString("reason"), rs.getString("before_json"),
                        rs.getString("after_json")))
                .list();
    }

    private ReferralSettings map(ResultSet rs, int n) throws SQLException {
        return new ReferralSettings(rs.getBoolean("enabled"),
                new ReferralReward(RewardType.valueOf(rs.getString("referrer_reward_type")),
                        rs.getLong("referrer_credit_minor"), rs.getInt("referrer_membership_days")),
                new ReferralReward(RewardType.valueOf(rs.getString("referred_reward_type")),
                        rs.getLong("referred_credit_minor"), rs.getInt("referred_membership_days")),
                ReferralSettings.QualifyingEvent.valueOf(rs.getString("qualifying_event")),
                rs.getLong("min_order_minor"), rs.getInt("return_window_days"),
                rs.getInt("max_rewarded_per_referrer"), rs.getLong("max_credit_per_customer_minor"),
                rs.getInt("validity_days"), rs.getString("updated_by"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
