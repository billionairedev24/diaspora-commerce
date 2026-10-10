package com.afrotransact.identity.adapter.out.session;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.UserSession;
import com.afrotransact.identity.port.SessionStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.stereotype.Component;

/**
 * Sessions are Spring Authorization Server authorizations (one per sign-in per app) whose refresh or access token
 * is still valid. Revoking deletes the authorization, so its refresh token stops working immediately.
 */
@Component
class JdbcSessionStore implements SessionStore {

    private final JdbcClient jdbc;
    private final OAuth2AuthorizationService authorizations;
    private final Clock clock;

    JdbcSessionStore(JdbcClient jdbc, OAuth2AuthorizationService authorizations, Clock clock) {
        this.jdbc = jdbc;
        this.authorizations = authorizations;
        this.clock = clock;
    }

    @Override
    public List<UserSession> list(UUID userId, Optional<String> currentAccessToken) {
        String currentId = currentAccessToken.map(this::authorizationId).orElse(null);
        return jdbc.sql("""
                select a.id, c.client_id, c.client_name,
                       coalesce(a.authorization_code_issued_at, a.access_token_issued_at) as signed_in_at,
                       coalesce(a.access_token_issued_at, a.authorization_code_issued_at) as last_active_at,
                       coalesce(a.refresh_token_expires_at, a.access_token_expires_at) as expires_at
                from oauth2_authorization a join oauth2_registered_client c on c.id = a.registered_client_id
                where a.principal_name = :u
                  and (a.refresh_token_expires_at > :now or a.access_token_expires_at > :now)
                order by last_active_at desc nulls last
                """)
                .param("u", userId.toString())
                .param("now", Timestamp.from(Instant.now(clock)))
                .query((rs, n) -> new UserSession(rs.getString("id"), rs.getString("client_id"),
                        rs.getString("client_name"), instant(rs.getTimestamp("signed_in_at")),
                        instant(rs.getTimestamp("last_active_at")), instant(rs.getTimestamp("expires_at")),
                        rs.getString("id").equals(currentId)))
                .list();
    }

    @Override
    public boolean revoke(UUID userId, String sessionId) {
        return jdbc.sql("delete from oauth2_authorization where id = :id and principal_name = :u")
                .param("id", sessionId).param("u", userId.toString()).update() == 1;
    }

    @Override
    public int revokeAll(UUID userId, Optional<String> keepAccessToken) {
        String keep = keepAccessToken.map(this::authorizationId).orElse("");
        return jdbc.sql("delete from oauth2_authorization where principal_name = :u and id <> :keep")
                .param("u", userId.toString()).param("keep", keep).update();
    }

    private String authorizationId(String accessToken) {
        OAuth2Authorization a = authorizations.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN);
        return a == null ? null : a.getId();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
