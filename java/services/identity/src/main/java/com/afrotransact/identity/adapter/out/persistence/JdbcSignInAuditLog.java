package com.afrotransact.identity.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.SignInAuditEntry;
import com.afrotransact.identity.port.SignInAuditLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSignInAuditLog implements SignInAuditLog {

    private static final int USER_AGENT_MAX = 300;

    private final JdbcClient jdbc;

    JdbcSignInAuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(SignInAuditEntry e) {
        jdbc.sql("""
                insert into sign_in_audit (occurred_at, user_id, email, client_id, channel, method, outcome, reason,
                    ip, user_agent)
                values (:at, :user, :email, :client, :channel, :method, :outcome, :reason, :ip, :ua)
                """)
                .param("at", Jdbc.ts(e.at())).param("user", e.userId()).param("email", cut(e.email(), 254))
                .param("client", cut(e.clientId(), 100)).param("channel", e.channel()).param("method", e.method())
                .param("outcome", e.outcome()).param("reason", cut(e.reason(), 80)).param("ip", cut(e.ip(), 64))
                .param("ua", cut(e.userAgent(), USER_AGENT_MAX))
                .update();
    }

    @Override
    public List<SignInAuditEntry> recent(UUID userId, int limit) {
        return jdbc.sql("select * from sign_in_audit where user_id = :u order by id desc limit :n")
                .param("u", userId).param("n", limit)
                .query((rs, n) -> new SignInAuditEntry(Jdbc.instant(rs, "occurred_at"),
                        rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("client_id"),
                        rs.getString("channel"), rs.getString("method"), rs.getString("outcome"),
                        rs.getString("reason"), rs.getString("ip"), rs.getString("user_agent")))
                .list();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
