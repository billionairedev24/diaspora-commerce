package com.afrotransact.identity.adapter.out.persistence;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import com.afrotransact.identity.domain.SessionAudience;
import com.afrotransact.identity.domain.SessionPolicyOverrides;
import com.afrotransact.identity.port.SessionPolicyStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSessionPolicyStore implements SessionPolicyStore {

    private final JdbcClient jdbc;

    JdbcSessionPolicyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<SessionAudience, SessionPolicyOverrides> loadAll() {
        Map<SessionAudience, SessionPolicyOverrides> out = new EnumMap<>(SessionAudience.class);
        jdbc.sql("select * from session_policy_override")
                .query((rs, n) -> new Row(rs.getString("audience"),
                        new SessionPolicyOverrides((Integer) rs.getObject("idle_timeout_minutes"),
                                (Integer) rs.getObject("absolute_timeout_minutes"),
                                (Integer) rs.getObject("warn_seconds"))))
                .list()
                .forEach(r -> SessionAudience.parse(r.audience()).ifPresent(a -> out.put(a, r.overrides())));
        return out;
    }

    @Override
    public void save(SessionAudience audience, SessionPolicyOverrides o, Instant at, String by) {
        if (o.isEmpty()) {
            jdbc.sql("delete from session_policy_override where audience = :audience")
                    .param("audience", audience.key()).update();
            return;
        }
        jdbc.sql("""
                insert into session_policy_override (audience, idle_timeout_minutes, absolute_timeout_minutes,
                    warn_seconds, updated_at, updated_by)
                values (:audience, :idle, :absolute, :warn, :at, :by)
                on conflict (audience) do update set idle_timeout_minutes = :idle,
                    absolute_timeout_minutes = :absolute, warn_seconds = :warn, updated_at = :at, updated_by = :by,
                    version = session_policy_override.version + 1
                """)
                .param("audience", audience.key())
                .param("idle", o.idleTimeoutMinutes())
                .param("absolute", o.absoluteTimeoutMinutes())
                .param("warn", o.warnSeconds())
                .param("at", Jdbc.ts(at))
                .param("by", by)
                .update();
    }

    private record Row(String audience, SessionPolicyOverrides overrides) {
    }
}
