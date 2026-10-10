package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.catalog.port.ListingSuppressionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcListingSuppressionRepository implements ListingSuppressionRepository {

    private final JdbcClient jdbc;

    JdbcListingSuppressionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean apply(String orgId, boolean hidden, String reason, Instant changedAt, Instant now) {
        Optional<Previous> prev = jdbc.sql("""
                select hidden, changed_at from org_listing_suppression where org_id = :org for update
                """).param("org", orgId)
                .query((rs, n) -> new Previous(rs.getBoolean("hidden"), rs.getTimestamp("changed_at").toInstant()))
                .optional();
        if (prev.isPresent() && prev.get().changedAt().isAfter(changedAt)) {
            return false;
        }
        jdbc.sql("""
                insert into org_listing_suppression (org_id, hidden, reason, changed_at, updated_at)
                values (:org, :hidden, :reason, :changedAt, :now)
                on conflict (org_id) do update set hidden = excluded.hidden, reason = excluded.reason,
                    changed_at = excluded.changed_at, updated_at = excluded.updated_at
                """)
                .param("org", orgId).param("hidden", hidden).param("reason", reason)
                .param("changedAt", Timestamp.from(changedAt)).param("now", Timestamp.from(now))
                .update();
        return prev.map(p -> p.hidden() != hidden).orElse(hidden);
    }

    private record Previous(boolean hidden, Instant changedAt) {
    }

    @Override
    public boolean isHidden(String orgId) {
        return jdbc.sql("select exists(select 1 from org_listing_suppression where org_id = :org and hidden)")
                .param("org", orgId).query(Boolean.class).single();
    }
}
