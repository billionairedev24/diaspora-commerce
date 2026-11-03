package com.afrotransact.payments.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.MembershipBenefitUse;
import com.afrotransact.payments.domain.MembershipEvent;
import com.afrotransact.payments.port.MembershipActivityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMembershipActivityRepository implements MembershipActivityRepository {

    private final JdbcClient jdbc;

    JdbcMembershipActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertUse(MembershipBenefitUse u) {
        return jdbc.sql("""
                insert into membership_benefit_use (id, membership_id, customer_id, tier_code, benefit, order_id, ref,
                    amount_minor, currency, note, created_at)
                values (:id, :membership, :customer, :tier, :benefit, :order, :ref, :amount, :currency, :note, :at)
                on conflict (benefit, order_id, ref) do nothing
                """)
                .param("id", u.id()).param("membership", u.membershipId()).param("customer", u.customerId())
                .param("tier", u.tierCode()).param("benefit", u.benefit().name()).param("order", u.orderId())
                .param("ref", u.ref()).param("amount", u.amount().minor()).param("currency", u.amount().currency())
                .param("note", u.note()).param("at", Sql.ts(u.createdAt())).update() == 1;
    }

    @Override
    public List<MembershipBenefitUse> usesOf(String customerId, int limit) {
        return jdbc.sql("""
                select id, membership_id, customer_id, tier_code, benefit, order_id, ref, amount_minor, currency, note,
                       created_at
                from membership_benefit_use where customer_id = :c order by created_at desc, id limit :n
                """)
                .param("c", customerId).param("n", limit)
                .query((rs, i) -> new MembershipBenefitUse(rs.getObject("id", UUID.class),
                        rs.getObject("membership_id", UUID.class), rs.getString("customer_id"),
                        rs.getString("tier_code"), BenefitCode.valueOf(rs.getString("benefit")),
                        rs.getString("order_id"), rs.getString("ref"),
                        Money.of(rs.getLong("amount_minor"), rs.getString("currency")), rs.getString("note"),
                        Sql.instant(rs, "created_at")))
                .list();
    }

    @Override
    public boolean anyUseSince(UUID membershipId, Instant since) {
        return jdbc.sql("select exists (select 1 from membership_benefit_use where membership_id = :m "
                + "and created_at >= :since)").param("m", membershipId).param("since", Sql.ts(since))
                .query(Boolean.class).single();
    }

    @Override
    public Optional<MembershipBenefitUse> useOnOrder(String orderId, String benefit) {
        return jdbc.sql("""
                select id, membership_id, customer_id, tier_code, benefit, order_id, ref, amount_minor, currency, note,
                       created_at
                from membership_benefit_use where order_id = :o and benefit = :b limit 1
                """)
                .param("o", orderId).param("b", benefit)
                .query((rs, i) -> new MembershipBenefitUse(rs.getObject("id", UUID.class),
                        rs.getObject("membership_id", UUID.class), rs.getString("customer_id"),
                        rs.getString("tier_code"), BenefitCode.valueOf(rs.getString("benefit")),
                        rs.getString("order_id"), rs.getString("ref"),
                        Money.of(rs.getLong("amount_minor"), rs.getString("currency")), rs.getString("note"),
                        Sql.instant(rs, "created_at")))
                .optional();
    }

    @Override
    public void addEvent(MembershipEvent e) {
        jdbc.sql("""
                insert into membership_event (id, membership_id, customer_id, type, tier_code, actor, detail,
                    occurred_at)
                values (:id, :membership, :customer, :type, :tier, :actor, :detail, :at)
                """)
                .param("id", e.id()).param("membership", e.membershipId()).param("customer", e.customerId())
                .param("type", e.type().name()).param("tier", e.tierCode()).param("actor", e.actor())
                .param("detail", e.detail()).param("at", Sql.ts(e.occurredAt())).update();
    }

    @Override
    public List<MembershipEvent> eventsOf(UUID membershipId) {
        return jdbc.sql("""
                select id, membership_id, customer_id, type, tier_code, actor, detail, occurred_at
                from membership_event where membership_id = :m order by occurred_at, id
                """)
                .param("m", membershipId)
                .query((rs, i) -> new MembershipEvent(rs.getObject("id", UUID.class),
                        rs.getObject("membership_id", UUID.class), rs.getString("customer_id"),
                        MembershipEvent.Type.valueOf(rs.getString("type")), rs.getString("tier_code"),
                        rs.getString("actor"), rs.getString("detail"), Sql.instant(rs, "occurred_at")))
                .list();
    }

    @Override
    public boolean claimTrial(String customerId, String cardKey, UUID membershipId, Instant at) {
        return jdbc.sql("""
                insert into membership_trial (customer_id, card_key, membership_id, started_at)
                values (:c, :k, :m, :at) on conflict do nothing
                """)
                .param("c", customerId).param("k", cardKey).param("m", membershipId).param("at", Sql.ts(at))
                .update() == 1;
    }

    @Override
    public boolean hadMembership(String customerId) {
        return jdbc.sql("select exists (select 1 from membership where customer_id = :c) "
                + "or exists (select 1 from membership_trial where customer_id = :c)").param("c", customerId)
                .query(Boolean.class).single();
    }

    @Override
    public Optional<UUID> claimGrant(String key, String customerId, String tierCode, int days, String reason,
                                     String by, UUID membershipId, Instant at) {
        int inserted = jdbc.sql("""
                insert into membership_grant (idempotency_key, customer_id, tier_code, days, reason, granted_by,
                    membership_id, created_at)
                values (:key, :c, :tier, :days, :reason, :by, :m, :at) on conflict do nothing
                """)
                .param("key", key).param("c", customerId).param("tier", tierCode).param("days", days)
                .param("reason", reason).param("by", by).param("m", membershipId).param("at", Sql.ts(at)).update();
        return inserted == 1 ? Optional.empty() : grantMembership(key);
    }

    @Override
    public List<String> deliveredOrdersAwaitingCashback(Instant since, int limit) {
        return jdbc.sql("""
                select s.order_id from settlement s
                join payment p on p.order_id = s.order_id and p.parent_payment_id is null
                join membership m on m.customer_id = p.shopper_id and m.status = 'ACTIVE'
                                 and m.started_at <= s.delivered_at
                where s.delivered_at >= :since
                  and exists (select 1 from membership_tier t, jsonb_array_elements(t.benefits) b
                              where t.code = m.tier_code and b ->> 'code' = 'CASHBACK')
                  and not exists (select 1 from membership_benefit_use u
                                  where u.order_id = s.order_id and u.benefit = 'CASHBACK')
                order by s.delivered_at desc limit :n
                """)
                .param("since", Sql.ts(since)).param("n", limit).query(String.class).list();
    }

    @Override
    public Optional<UUID> grantMembership(String key) {
        return jdbc.sql("select membership_id from membership_grant where idempotency_key = :k").param("k", key)
                .query(UUID.class).optional();
    }
}
