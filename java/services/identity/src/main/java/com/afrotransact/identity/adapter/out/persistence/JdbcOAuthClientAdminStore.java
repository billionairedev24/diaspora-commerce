package com.afrotransact.identity.adapter.out.persistence;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.security.PublicClientRefreshTokenAuthenticationProvider;
import com.afrotransact.identity.domain.OAuthClient;
import com.afrotransact.identity.port.OAuthClientAdminStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the clients Spring Authorization Server stores ({@code oauth2_registered_client}) through its own
 * JDBC repository, plus {@code oauth_client_state} (enabled switch, previous secret during a rotation).
 */
@Repository
class JdbcOAuthClientAdminStore implements OAuthClientAdminStore {

    private final JdbcRegisteredClientRepository registered;
    private final JdbcClient jdbc;

    JdbcOAuthClientAdminStore(JdbcRegisteredClientRepository registered, JdbcClient jdbc) {
        this.registered = registered;
        this.jdbc = jdbc;
    }

    private record State(boolean enabled, Instant rotatedAt, Instant previousUntil) {
    }

    @Override
    public List<OAuthClient> all() {
        Map<String, State> states = new HashMap<>();
        jdbc.sql("select client_id, enabled, secret_rotated_at, previous_secret_expires_at from oauth_client_state")
                .query((rs, n) -> states.put(rs.getString("client_id"), new State(rs.getBoolean("enabled"),
                        Jdbc.instant(rs, "secret_rotated_at"), Jdbc.instant(rs, "previous_secret_expires_at"))))
                .list();
        return jdbc.sql("select client_id from oauth2_registered_client order by client_name, client_id")
                .query(String.class).list().stream()
                .map(id -> toClient(registered.findByClientId(id), states.get(id))).toList();
    }

    @Override
    public Optional<OAuthClient> find(String clientId) {
        RegisteredClient c = registered.findByClientId(clientId);
        if (c == null) {
            return Optional.empty();
        }
        State state = jdbc.sql("select enabled, secret_rotated_at, previous_secret_expires_at from oauth_client_state "
                        + "where client_id = :id").param("id", clientId)
                .query((rs, n) -> new State(rs.getBoolean("enabled"), Jdbc.instant(rs, "secret_rotated_at"),
                        Jdbc.instant(rs, "previous_secret_expires_at"))).optional().orElse(null);
        return Optional.of(toClient(c, state));
    }

    @Override
    public void create(OAuthClient client, String secretHash, Instant at, String by) {
        RegisteredClient.Builder b = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(client.clientId()).clientName(client.name()).clientIdIssuedAt(at);
        if (client.confidential()) {
            b.clientSecret(secretHash)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        } else {
            b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
        fill(b, client);
        b.clientSettings(clientSettings(ClientSettings.builder(), client).build());
        b.tokenSettings(tokenSettings(TokenSettings.builder(), client).build());
        registered.save(b.build());
        saveState(client.clientId(), true, at, by);
    }

    @Override
    public void update(OAuthClient client) {
        RegisteredClient existing = registered.findByClientId(client.clientId());
        RegisteredClient.Builder b = RegisteredClient.from(existing).clientName(client.name());
        b.authorizationGrantTypes(g -> g.clear());
        b.redirectUris(u -> u.clear());
        b.postLogoutRedirectUris(u -> u.clear());
        b.scopes(s -> s.clear());
        fill(b, client);
        b.clientSettings(clientSettings(ClientSettings.withSettings(existing.getClientSettings().getSettings()),
                client).build());
        b.tokenSettings(tokenSettings(TokenSettings.withSettings(existing.getTokenSettings().getSettings()), client)
                .build());
        registered.save(b.build());
    }

    @Override
    public void setEnabled(String clientId, boolean enabled, Instant at, String by) {
        jdbc.sql("""
                insert into oauth_client_state (client_id, enabled, updated_at, updated_by)
                values (:id, :enabled, :at, :by)
                on conflict (client_id) do update set enabled = :enabled, updated_at = :at, updated_by = :by
                """).param("id", clientId).param("enabled", enabled).param("at", Jdbc.ts(at)).param("by", by).update();
    }

    @Override
    public void replaceSecret(String clientId, String newSecretHash, Instant previousValidUntil, Instant at,
                              String by) {
        RegisteredClient existing = registered.findByClientId(clientId);
        String previous = existing.getClientSecret();
        registered.save(RegisteredClient.from(existing).clientSecret(newSecretHash).build());
        jdbc.sql("""
                insert into oauth_client_state (client_id, enabled, previous_secret_hash, previous_secret_expires_at,
                    secret_rotated_at, updated_at, updated_by)
                values (:id, true, :prev, :until, :at, :at, :by)
                on conflict (client_id) do update set previous_secret_hash = :prev,
                    previous_secret_expires_at = :until, secret_rotated_at = :at, updated_at = :at, updated_by = :by
                """).param("id", clientId).param("prev", previous).param("until", Jdbc.ts(previousValidUntil))
                .param("at", Jdbc.ts(at)).param("by", by).update();
    }

    private void saveState(String clientId, boolean enabled, Instant at, String by) {
        setEnabled(clientId, enabled, at, by);
    }

    private static void fill(RegisteredClient.Builder b, OAuthClient c) {
        c.grantTypes().forEach(g -> b.authorizationGrantType(new AuthorizationGrantType(g)));
        c.redirectUris().forEach(b::redirectUri);
        c.postLogoutRedirectUris().forEach(b::postLogoutRedirectUri);
        c.scopes().forEach(b::scope);
    }

    private static ClientSettings.Builder clientSettings(ClientSettings.Builder b, OAuthClient c) {
        return b.requireProofKey(c.requirePkce()).requireAuthorizationConsent(c.requireConsent())
                .setting(PublicClientRefreshTokenAuthenticationProvider.PUBLIC_REFRESH_TOKENS,
                        c.publicRefreshTokens());
    }

    private static TokenSettings.Builder tokenSettings(TokenSettings.Builder b, OAuthClient c) {
        return b.accessTokenTimeToLive(c.accessTokenTtl()).refreshTokenTimeToLive(c.refreshTokenTtl())
                .reuseRefreshTokens(false);
    }

    private static OAuthClient toClient(RegisteredClient c, State state) {
        boolean confidential = c.getClientAuthenticationMethods().stream()
                .anyMatch(m -> !ClientAuthenticationMethod.NONE.equals(m));
        boolean publicRefresh = Boolean.TRUE.equals(
                c.getClientSettings().getSetting(PublicClientRefreshTokenAuthenticationProvider.PUBLIC_REFRESH_TOKENS));
        return new OAuthClient(c.getClientId(), c.getClientName(), confidential,
                c.getAuthorizationGrantTypes().stream().map(AuthorizationGrantType::getValue).sorted().toList(),
                c.getScopes().stream().sorted().toList(), c.getRedirectUris().stream().sorted().toList(),
                c.getPostLogoutRedirectUris().stream().sorted().toList(),
                c.getClientSettings().isRequireProofKey(), c.getClientSettings().isRequireAuthorizationConsent(),
                publicRefresh, c.getTokenSettings().getAccessTokenTimeToLive(),
                c.getTokenSettings().getRefreshTokenTimeToLive(), state == null || state.enabled(),
                c.getClientIdIssuedAt(), state == null ? null : state.rotatedAt(),
                state == null ? null : state.previousUntil());
    }
}
