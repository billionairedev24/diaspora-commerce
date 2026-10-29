package com.afrotransact.identity.adapter.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.identity.application.AuthSettingsService;
import com.afrotransact.identity.domain.AuthSettings;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * The repository the authorization server reads clients from. On top of the stored client it applies what the admin
 * console governs (S291):
 * <ul>
 *   <li>a disabled client is not found, so the token endpoint answers {@code invalid_client} and its refresh tokens
 *   cannot be used;</li>
 *   <li>token lifetimes are capped by the auth settings;</li>
 *   <li>during a secret rotation the client's secret reads as a {@code {rotating}} value holding the new and the
 *   previous hash, which {@code RotatingSecretEncoder} accepts either of until the previous one expires.</li>
 * </ul>
 * Nothing here is written back: the authorization server only saves a client to re-hash a secret after a successful
 * login, and hashes are managed by the admin service, so {@link #save} is deliberately a no-op.
 */
public class GovernedRegisteredClientRepository implements RegisteredClientRepository {

    private final RegisteredClientRepository stored;
    private final JdbcClient jdbc;
    private final AuthSettingsService settings;
    private final Clock clock;

    public GovernedRegisteredClientRepository(RegisteredClientRepository stored, JdbcClient jdbc,
                                              AuthSettingsService settings, Clock clock) {
        this.stored = stored;
        this.jdbc = jdbc;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        // See the class comment.
    }

    @Override
    public RegisteredClient findById(String id) {
        return govern(stored.findById(id));
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return govern(stored.findByClientId(clientId));
    }

    private RegisteredClient govern(RegisteredClient client) {
        if (client == null) {
            return null;
        }
        Optional<State> state = jdbc.sql("""
                select enabled, previous_secret_hash, previous_secret_expires_at from oauth_client_state
                where client_id = :id
                """).param("id", client.getClientId())
                .query((rs, n) -> new State(rs.getBoolean("enabled"), rs.getString("previous_secret_hash"),
                        Jdbc.instant(rs, "previous_secret_expires_at")))
                .optional();
        if (state.isPresent() && !state.get().enabled()) {
            return null;
        }
        AuthSettings s = settings.effective();
        TokenSettings tokens = client.getTokenSettings();
        RegisteredClient.Builder b = RegisteredClient.from(client).tokenSettings(TokenSettings
                .withSettings(tokens.getSettings())
                .accessTokenTimeToLive(s.capAccess(tokens.getAccessTokenTimeToLive()))
                .refreshTokenTimeToLive(s.capRefresh(tokens.getRefreshTokenTimeToLive())).build());
        Instant now = Instant.now(clock);
        if (state.isPresent() && client.getClientSecret() != null && state.get().previousHash() != null
                && state.get().previousUntil() != null && state.get().previousUntil().isAfter(now)) {
            b.clientSecret(RotatingSecretEncoder.compose(state.get().previousUntil(), client.getClientSecret(),
                    state.get().previousHash()));
        }
        return b.build();
    }

    private record State(boolean enabled, String previousHash, Instant previousUntil) {
    }
}
