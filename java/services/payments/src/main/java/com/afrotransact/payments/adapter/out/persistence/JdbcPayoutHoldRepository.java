package com.afrotransact.payments.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;

import com.afrotransact.payments.domain.PayoutHold;
import com.afrotransact.payments.port.PayoutHoldRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPayoutHoldRepository implements PayoutHoldRepository {

    private final JdbcClient jdbc;

    JdbcPayoutHoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean apply(PayoutHold h, Instant now) {
        return jdbc.sql("""
                insert into payout_hold (org_id, account_id, paused, reason, actor_id, note, changed_at, updated_at)
                values (:org, :acct, :paused, :reason, :actor, :note, :changedAt, :now)
                on conflict (org_id) do update set account_id = coalesce(excluded.account_id, payout_hold.account_id),
                    paused = excluded.paused, reason = excluded.reason, actor_id = excluded.actor_id,
                    note = excluded.note, changed_at = excluded.changed_at, updated_at = excluded.updated_at
                where payout_hold.changed_at <= excluded.changed_at
                """)
                .param("org", h.orgId()).param("acct", h.accountId()).param("paused", h.paused())
                .param("reason", h.reason()).param("actor", h.actorId()).param("note", h.note())
                .param("changedAt", Sql.ts(h.changedAt())).param("now", Sql.ts(now))
                .update() == 1;
    }

    @Override
    public Optional<PayoutHold> find(String orgId) {
        return jdbc.sql("select * from payout_hold where org_id = :org").param("org", orgId)
                .query((rs, n) -> new PayoutHold(rs.getString("org_id"), rs.getString("account_id"),
                        rs.getBoolean("paused"), rs.getString("reason"), rs.getString("actor_id"),
                        rs.getString("note"), Sql.instant(rs, "changed_at")))
                .optional();
    }
}
