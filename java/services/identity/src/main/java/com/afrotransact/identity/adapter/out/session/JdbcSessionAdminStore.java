package com.afrotransact.identity.adapter.out.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.afrotransact.identity.domain.AdminSession;
import com.afrotransact.identity.port.SessionAdminStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sign-ins are Spring Authorization Server authorizations. Deleting the row revokes its refresh token at once (the
 * token endpoint looks it up by value). The IP comes from the web authentication details saved with the principal.
 */
@Component
class JdbcSessionAdminStore implements SessionAdminStore {

    private static final String BASE = """
            from oauth2_authorization a
            join oauth2_registered_client c on c.id = a.registered_client_id
            left join user_account u on u.id::text = a.principal_name
            where (a.refresh_token_expires_at > :now or a.access_token_expires_at > :now)
              and (cast(:user as varchar) is null or a.principal_name = :user)
              and (cast(:client as varchar) is null or c.client_id = :client)
              and (cast(:like as varchar) is null
                   or lower(u.email) like :like escape '\\'
                   or lower(u.first_name || ' ' || u.last_name) like :like escape '\\'
                   or lower(c.client_id) like :like escape '\\'
                   or lower(c.client_name) like :like escape '\\')
            """;
    private static final String COLUMNS = """
            select a.id, a.principal_name, c.client_id, c.client_name, u.first_name, u.last_name, u.email,
                   a.attributes,
                   coalesce(a.authorization_code_issued_at, a.access_token_issued_at) as signed_in_at,
                   coalesce(a.access_token_issued_at, a.authorization_code_issued_at) as last_used_at,
                   coalesce(a.refresh_token_expires_at, a.access_token_expires_at) as expires_at,
                   a.refresh_token_value is not null as refreshable
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcSessionAdminStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page search(String userId, String clientId, String text, Instant now, int limit, int offset) {
        String like = text == null ? null : "%" + escape(text.toLowerCase(Locale.ROOT)) + "%";
        Long total = jdbc.sql("select count(*) " + BASE)
                .param("now", Timestamp.from(now)).param("user", userId).param("client", clientId).param("like", like)
                .query(Long.class).single();
        List<AdminSession> items = jdbc.sql(COLUMNS + BASE + " order by last_used_at desc nulls last, a.id limit :limit offset :offset")
                .param("now", Timestamp.from(now)).param("user", userId).param("client", clientId).param("like", like)
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> map(rs)).list();
        return new Page(items, total == null ? 0 : total);
    }

    @Override
    public Optional<AdminSession> find(String id) {
        return jdbc.sql(COLUMNS + """
                from oauth2_authorization a
                join oauth2_registered_client c on c.id = a.registered_client_id
                left join user_account u on u.id::text = a.principal_name
                where a.id = :id
                """).param("id", id).query((rs, n) -> map(rs)).optional();
    }

    @Override
    public int revoke(String id) {
        return jdbc.sql("delete from oauth2_authorization where id = :id").param("id", id).update();
    }

    @Override
    public int revokeAllOfUser(String userId) {
        return jdbc.sql("delete from oauth2_authorization where principal_name = :u").param("u", userId).update();
    }

    @Override
    public int revokeAllOfClient(String clientId) {
        return jdbc.sql("""
                delete from oauth2_authorization
                where registered_client_id in (select id from oauth2_registered_client where client_id = :c)
                """).param("c", clientId).update();
    }

    private AdminSession map(ResultSet rs) throws SQLException {
        String first = rs.getString("first_name");
        String name = first == null ? null : (first + " " + rs.getString("last_name")).trim();
        return new AdminSession(rs.getString("id"), rs.getString("principal_name"), name, rs.getString("email"),
                rs.getString("client_id"), rs.getString("client_name"), remoteAddress(rs.getString("attributes")),
                instant(rs.getTimestamp("signed_in_at")), instant(rs.getTimestamp("last_used_at")),
                instant(rs.getTimestamp("expires_at")), rs.getBoolean("refreshable"));
    }

    /** The first {@code remoteAddress} anywhere in the saved principal (WebAuthenticationDetails), if any. */
    private String remoteAddress(String attributes) {
        if (attributes == null || !attributes.contains("remoteAddress")) {
            return null;
        }
        try {
            return find(json.readTree(attributes));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String find(JsonNode node) {
        JsonNode direct = node.get("remoteAddress");
        if (direct != null && direct.isString() && !direct.asString().isBlank()) {
            return direct.asString();
        }
        for (JsonNode child : node.values()) {
            String found = find(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
