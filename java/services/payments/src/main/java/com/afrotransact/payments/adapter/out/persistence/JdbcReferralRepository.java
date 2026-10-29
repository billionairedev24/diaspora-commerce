package com.afrotransact.payments.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Referral;
import com.afrotransact.payments.domain.ReferralFlag;
import com.afrotransact.payments.domain.ReferralReward;
import com.afrotransact.payments.domain.ReferralRewardGrant;
import com.afrotransact.payments.domain.ReferralSide;
import com.afrotransact.payments.domain.ReferralStatus;
import com.afrotransact.payments.domain.RewardType;
import com.afrotransact.payments.port.ReferralRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReferralRepository implements ReferralRepository {

    private static final String SELECT = """
            select id, referrer_id, referred_id, code, referred_name, status, array_to_string(flags, ',') as flags,
                   signed_up_at, expires_at, qualifying_order_id, qualifying_minor, delivered_at, eligible_at,
                   decision, decision_reason, decided_by, decided_at, created_at, updated_at, version
            from referral
            """;
    private static final String REWARD_SELECT = """
            select referral_id, side, customer_id, reward_type, credit_minor, membership_days, currency, status,
                   entry_id, granted_at, idempotency_key
            from referral_reward
            """;

    private final JdbcClient jdbc;

    JdbcReferralRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ codes

    @Override
    public Optional<String> codeOf(String customerId) {
        return jdbc.sql("select code from referral_code where customer_id = :c").param("c", customerId)
                .query(String.class).optional();
    }

    @Override
    public Optional<String> customerOfCode(String code) {
        return jdbc.sql("select customer_id from referral_code where code = :c").param("c", code)
                .query(String.class).optional();
    }

    @Override
    public boolean insertCode(String customerId, String code, Instant now) {
        return jdbc.sql("""
                insert into referral_code (customer_id, code, created_at) values (:c, :code, :at)
                on conflict do nothing
                """).param("c", customerId).param("code", code).param("at", Sql.ts(now)).update() == 1;
    }

    // ------------------------------------------------------------------ referrals

    @Override
    public boolean insert(Referral r) {
        try {
            return jdbc.sql("""
                    insert into referral (id, referrer_id, referred_id, code, referred_name, status, flags,
                        signed_up_at, expires_at, created_at, updated_at, version)
                    values (:id, :referrer, :referred, :code, :name, :status, string_to_array(:flags, ','),
                        :signedUp, :expires, :created, :updated, 0)
                    on conflict (referred_id) do nothing
                    """)
                    .param("id", r.id()).param("referrer", r.referrerId()).param("referred", r.referredId())
                    .param("code", r.code()).param("name", r.referredName()).param("status", r.status().name())
                    .param("flags", joinFlags(r.flags())).param("signedUp", Sql.ts(r.signedUpAt()))
                    .param("expires", Sql.ts(r.expiresAt())).param("created", Sql.ts(r.createdAt()))
                    .param("updated", Sql.ts(r.updatedAt())).update() == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public Optional<Referral> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Referral> lock(UUID id) {
        return jdbc.sql(SELECT + " where id = :id for update").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Referral> findByReferred(String referredId) {
        return jdbc.sql(SELECT + " where referred_id = :c").param("c", referredId).query(this::map).optional();
    }

    @Override
    public Referral update(Referral r) {
        int updated = jdbc.sql("""
                update referral set status = :status, flags = string_to_array(:flags, ','),
                    qualifying_order_id = :order, qualifying_minor = :minor, delivered_at = :delivered,
                    eligible_at = :eligible, decision = :decision, decision_reason = :reason,
                    decided_by = :by, decided_at = :decidedAt, updated_at = :updated, version = version + 1
                where id = :id and version = :version
                """)
                .param("status", r.status().name()).param("flags", joinFlags(r.flags()))
                .param("order", r.qualifyingOrderId()).param("minor", r.qualifyingMinor())
                .param("delivered", Sql.ts(r.deliveredAt())).param("eligible", Sql.ts(r.eligibleAt()))
                .param("decision", r.decision() == null ? null : r.decision().name())
                .param("reason", r.decisionReason()).param("by", r.decidedBy()).param("decidedAt", Sql.ts(r.decidedAt()))
                .param("updated", Sql.ts(r.updatedAt())).param("id", r.id()).param("version", r.version()).update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Referral " + r.id() + " was changed concurrently");
        }
        return find(r.id()).orElseThrow();
    }

    @Override
    public List<Referral> ofReferrer(String referrerId, int limit) {
        return jdbc.sql(SELECT + " where referrer_id = :c order by created_at desc, id limit :n")
                .param("c", referrerId).param("n", limit).query(this::map).list();
    }

    @Override
    public List<Referral> search(ReferralStatus status, String customerId, int limit) {
        return jdbc.sql(SELECT + """
                where (cast(:status as text) is null or status = :status)
                  and (cast(:c as text) is null or referrer_id = :c or referred_id = :c)
                order by created_at desc, id limit :n
                """)
                .param("status", status == null ? null : status.name()).param("c", customerId).param("n", limit)
                .query(this::map).list();
    }

    @Override
    public List<UUID> due(Instant now, int limit) {
        return jdbc.sql("""
                select id from referral where status in ('WAITING', 'PARTIALLY_REWARDED') and eligible_at <= :now
                order by eligible_at, id limit :n
                """).param("now", Sql.ts(now)).param("n", limit).query(UUID.class).list();
    }

    @Override
    public List<UUID> expiring(Instant now, int limit) {
        return jdbc.sql("""
                select id from referral where status = 'PENDING' and expires_at <= :now
                order by expires_at, id limit :n
                """).param("now", Sql.ts(now)).param("n", limit).query(UUID.class).list();
    }

    // ------------------------------------------------------------------ rewards

    @Override
    public void saveRewards(List<ReferralRewardGrant> rewards) {
        for (ReferralRewardGrant g : rewards) {
            jdbc.sql("""
                    insert into referral_reward (referral_id, side, customer_id, reward_type, credit_minor,
                        membership_days, currency, status, entry_id, granted_at, idempotency_key)
                    values (:referral, :side, :customer, :type, :credit, :days, :currency, :status, :entry, :at, :key)
                    on conflict (referral_id, side) do nothing
                    """)
                    .param("referral", g.referralId()).param("side", g.side().name()).param("customer", g.customerId())
                    .param("type", g.reward().type().name()).param("credit", g.reward().creditMinor())
                    .param("days", g.reward().membershipDays()).param("currency", g.currency())
                    .param("status", g.status().name()).param("entry", g.entryId()).param("at", Sql.ts(g.grantedAt()))
                    .param("key", g.idempotencyKey()).update();
        }
    }

    @Override
    public void updateReward(ReferralRewardGrant g) {
        jdbc.sql("""
                update referral_reward set status = :status, entry_id = :entry, granted_at = :at, credit_minor = :credit,
                    membership_days = :days
                where referral_id = :referral and side = :side
                """)
                .param("status", g.status().name()).param("credit", g.reward().creditMinor())
                .param("days", g.reward().membershipDays()).param("entry", g.entryId()).param("at", Sql.ts(g.grantedAt()))
                .param("referral", g.referralId()).param("side", g.side().name()).update();
    }

    @Override
    public List<ReferralRewardGrant> rewards(UUID referralId) {
        return jdbc.sql(REWARD_SELECT + " where referral_id = :id order by side desc").param("id", referralId)
                .query(this::mapReward).list();
    }

    @Override
    public Map<UUID, List<ReferralRewardGrant>> rewardsOf(Collection<UUID> referralIds) {
        Map<UUID, List<ReferralRewardGrant>> out = new HashMap<>();
        if (referralIds.isEmpty()) {
            return out;
        }
        jdbc.sql(REWARD_SELECT + " where referral_id in (:ids) order by side desc").param("ids", referralIds)
                .query(this::mapReward).list()
                .forEach(g -> out.computeIfAbsent(g.referralId(), k -> new ArrayList<>()).add(g));
        return out;
    }

    @Override
    public long creditGrantedTo(String customerId) {
        return jdbc.sql("""
                select coalesce(sum(credit_minor), 0) from referral_reward
                where customer_id = :c and status = 'GRANTED' and reward_type = 'CREDIT'
                """).param("c", customerId).query(Long.class).single();
    }

    @Override
    public int rewardedCount(String referrerId) {
        return jdbc.sql("""
                select count(*) from referral_reward
                where customer_id = :c and side = 'REFERRER' and status = 'GRANTED'
                """).param("c", referrerId).query(Integer.class).single();
    }

    // ------------------------------------------------------------------ figures

    @Override
    public Earnings earnings(String customerId) {
        int invited = jdbc.sql("select count(*) from referral where referrer_id = :c").param("c", customerId)
                .query(Integer.class).single();
        int open = jdbc.sql("""
                select count(*) from referral where referrer_id = :c
                  and status in ('PENDING', 'WAITING', 'IN_REVIEW', 'PARTIALLY_REWARDED')
                """).param("c", customerId).query(Integer.class).single();
        int rewarded = rewardedCount(customerId);
        long earned = creditGrantedTo(customerId);
        int days = jdbc.sql("""
                select coalesce(sum(membership_days), 0) from referral_reward
                where customer_id = :c and status = 'GRANTED' and reward_type = 'MEMBERSHIP_DAYS'
                """).param("c", customerId).query(Integer.class).single();
        long pending = jdbc.sql("""
                select coalesce(sum(credit_minor), 0) from referral_reward
                where customer_id = :c and status in ('PENDING', 'DEFERRED') and reward_type = 'CREDIT'
                  and referral_id in (select id from referral where status in ('WAITING', 'PARTIALLY_REWARDED'))
                """).param("c", customerId).query(Long.class).single();
        return new Earnings(invited, open, rewarded, earned, days, pending);
    }

    @Override
    public Totals totals() {
        long cost = jdbc.sql("""
                select coalesce(sum(credit_minor), 0) from referral_reward
                where status = 'GRANTED' and reward_type = 'CREDIT'
                """).query(Long.class).single();
        int days = jdbc.sql("""
                select coalesce(sum(membership_days), 0) from referral_reward
                where status = 'GRANTED' and reward_type = 'MEMBERSHIP_DAYS'
                """).query(Integer.class).single();
        long pending = jdbc.sql("""
                select coalesce(sum(credit_minor), 0) from referral_reward
                where status in ('PENDING', 'DEFERRED') and reward_type = 'CREDIT'
                  and referral_id in (select id from referral where status in ('WAITING', 'PARTIALLY_REWARDED'))
                """).query(Long.class).single();
        Map<ReferralStatus, Integer> byStatus = new EnumMap<>(ReferralStatus.class);
        for (ReferralStatus s : ReferralStatus.values()) {
            byStatus.put(s, 0);
        }
        jdbc.sql("select status, count(*) as n from referral group by status").query((rs, n) -> {
            byStatus.put(ReferralStatus.valueOf(rs.getString("status")), rs.getInt("n"));
            return null;
        }).list();
        return new Totals(cost, days, pending, byStatus);
    }

    // ------------------------------------------------------------------ mapping

    private static String joinFlags(List<ReferralFlag> flags) {
        return String.join(",", flags.stream().map(Enum::name).toList());
    }

    private Referral map(ResultSet rs, int n) throws SQLException {
        String flagText = rs.getString("flags");
        List<ReferralFlag> flags = flagText == null || flagText.isBlank() ? List.of()
                : Arrays.stream(flagText.split(",")).map(ReferralFlag::valueOf).toList();
        long minor = rs.getLong("qualifying_minor");
        Long qualifying = rs.wasNull() ? null : minor;
        String decision = rs.getString("decision");
        return new Referral(rs.getObject("id", UUID.class), rs.getString("referrer_id"), rs.getString("referred_id"),
                rs.getString("code"), rs.getString("referred_name"), ReferralStatus.valueOf(rs.getString("status")),
                flags, Sql.instant(rs, "signed_up_at"), Sql.instant(rs, "expires_at"),
                rs.getString("qualifying_order_id"), qualifying, Sql.instant(rs, "delivered_at"),
                Sql.instant(rs, "eligible_at"), decision == null ? null : Referral.Decision.valueOf(decision),
                rs.getString("decision_reason"), rs.getString("decided_by"), Sql.instant(rs, "decided_at"),
                Sql.instant(rs, "created_at"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }

    private ReferralRewardGrant mapReward(ResultSet rs, int n) throws SQLException {
        return new ReferralRewardGrant(rs.getObject("referral_id", UUID.class),
                ReferralSide.valueOf(rs.getString("side")), rs.getString("customer_id"),
                new ReferralReward(RewardType.valueOf(rs.getString("reward_type")), rs.getLong("credit_minor"),
                        rs.getInt("membership_days")),
                rs.getString("currency"), ReferralRewardGrant.Status.valueOf(rs.getString("status")),
                rs.getObject("entry_id", UUID.class), Sql.instant(rs, "granted_at"), rs.getString("idempotency_key"));
    }
}
