package com.afrotransact.identity.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.AccessChange;
import com.afrotransact.identity.domain.AccessLogFilter;
import com.afrotransact.identity.port.AccessChangeLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccessChangeLog implements AccessChangeLog {

    /** Exact matches (indexed): person, role, permission; a null parameter leaves that filter off. */
    private static final String WHERE = """
             where (cast(:user as uuid) is null or target_user = :user)
               and (cast(:role as varchar) is null or target_role = :role)
               and (cast(:permission as varchar) is null or subject = :permission)
            """;

    private final JdbcClient jdbc;

    JdbcAccessChangeLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(String actorId, String action, UUID targetUserId, String targetRole, String subject,
                       String detail) {
        jdbc.sql("""
                insert into access_change_log (actor_id, action, target_user, target_role, subject, detail)
                values (:actor, :action, :user, :role, :subject, :detail)
                """)
                .param("actor", actorId)
                .param("action", action)
                .param("user", targetUserId)
                .param("role", targetRole)
                .param("subject", subject)
                .param("detail", detail == null ? null : detail.substring(0, Math.min(2000, detail.length())))
                .update();
    }

    @Override
    public List<AccessChange> recent(UUID targetUserId, int limit, int offset) {
        return jdbc.sql("""
                select * from access_change_log
                where cast(:user as uuid) is null or target_user = :user
                order by id desc limit :limit offset :offset
                """)
                .param("user", targetUserId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new AccessChange(rs.getLong("id"), Jdbc.instant(rs, "at"), rs.getString("actor_id"),
                        rs.getString("action"), rs.getObject("target_user", UUID.class), rs.getString("target_role"),
                        rs.getString("subject"), rs.getString("detail")))
                .list();
    }

    @Override
    public List<AccessChange> search(AccessLogFilter f, int limit, int offset) {
        return jdbc.sql("select * from access_change_log" + WHERE + " order by id desc limit :limit offset :offset")
                .param("user", f.userId())
                .param("role", f.role())
                .param("permission", f.permission())
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new AccessChange(rs.getLong("id"), Jdbc.instant(rs, "at"), rs.getString("actor_id"),
                        rs.getString("action"), rs.getObject("target_user", UUID.class), rs.getString("target_role"),
                        rs.getString("subject"), rs.getString("detail")))
                .list();
    }

    @Override
    public long count(AccessLogFilter f) {
        return jdbc.sql("select count(*) from access_change_log" + WHERE)
                .param("user", f.userId())
                .param("role", f.role())
                .param("permission", f.permission())
                .query(Long.class).single();
    }
}
