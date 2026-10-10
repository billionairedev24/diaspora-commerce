package com.afrotransact.bookings.adapter.out.persistence;

import java.time.Instant;

import com.afrotransact.bookings.port.OrgVisibilityRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrgVisibilityRepository implements OrgVisibilityRepository {

    private final JdbcClient jdbc;

    JdbcOrgVisibilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isHidden(String orgId) {
        return jdbc.sql("select hidden from org_visibility where org_id = :org").param("org", orgId)
                .query(Boolean.class).optional().orElse(false);
    }

    @Override
    public boolean apply(String orgId, boolean hidden, String reason, Instant occurredAt) {
        return jdbc.sql("""
                insert into org_visibility (org_id, hidden, reason, occurred_at, updated_at)
                values (:org, :hidden, :reason, :at, now())
                on conflict (org_id) do update
                    set hidden = excluded.hidden, reason = excluded.reason, occurred_at = excluded.occurred_at,
                        updated_at = now()
                    where org_visibility.occurred_at <= excluded.occurred_at
                """)
                .param("org", orgId).param("hidden", hidden).param("reason", reason).param("at", Sql.ts(occurredAt))
                .update() == 1;
    }
}
