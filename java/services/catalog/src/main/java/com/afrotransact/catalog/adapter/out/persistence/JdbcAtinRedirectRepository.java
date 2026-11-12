package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.catalog.port.AtinRedirectRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAtinRedirectRepository implements AtinRedirectRepository {

    private final JdbcClient jdbc;

    JdbcAtinRedirectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveAll(Map<String, String> fromTo, String mergedBy, String reason, Instant at) {
        fromTo.forEach((from, to) -> jdbc.sql("""
                insert into atin_redirect (from_atin, to_atin, merged_by, reason, merged_at)
                values (:from, :to, :by, :reason, :at) on conflict (from_atin) do nothing
                """).param("from", from).param("to", to).param("by", mergedBy).param("reason", reason)
                .param("at", Timestamp.from(at)).update());
    }

    @Override
    public Optional<String> find(String fromAtin) {
        return jdbc.sql("select to_atin from atin_redirect where from_atin = :from").param("from", fromAtin)
                .query(String.class).optional().map(String::trim);
    }

    @Override
    public void repoint(String oldTarget, String newTarget) {
        jdbc.sql("update atin_redirect set to_atin = :new where to_atin = :old").param("new", newTarget)
                .param("old", oldTarget).update();
    }
}
