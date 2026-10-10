package com.afrotransact.identity.config;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.security.PublicClientRefreshTokenAuthenticationProvider;
import com.afrotransact.identity.application.BootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Seeds the OAuth clients configured for this environment ({@code at.identity.clients}) and the bootstrap super
 * administrator. Clients are upserted by client id, so configuration changes apply on the next deploy.
 */
@Component
class StartupProvisioning implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StartupProvisioning.class);

    private final IdentityProperties props;
    private final RegisteredClientRepository clients;
    private final PasswordEncoder encoder;
    private final BootstrapService bootstrap;

    StartupProvisioning(IdentityProperties props, RegisteredClientRepository clients, PasswordEncoder encoder,
                        BootstrapService bootstrap) {
        this.props = props;
        this.clients = clients;
        this.encoder = encoder;
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (IdentityProperties.Client c : props.clients()) {
            if (!c.isPublic() && (c.secret() == null || c.secret().isBlank())) {
                LOG.warn("Skipping confidential client {}: no secret configured", c.clientId());
                continue;
            }
            clients.save(toRegisteredClient(c));
        }
        LOG.info("Seeded {} OAuth clients", props.clients().size());
        IdentityProperties.BootstrapAdmin admin = props.bootstrapAdmin();
        if (admin.email() != null && !admin.email().isBlank() && admin.password() != null
                && !admin.password().isBlank()) {
            bootstrap.ensureSuperAdmin(admin.email(), admin.password(),
                    admin.firstName() == null ? "Platform" : admin.firstName(),
                    admin.lastName() == null ? "Administrator" : admin.lastName(), admin.phone());
        }
    }

    private RegisteredClient toRegisteredClient(IdentityProperties.Client c) {
        RegisteredClient existing = clients.findByClientId(c.clientId());
        String id = existing != null ? existing.getId() : UUID.randomUUID().toString();
        RegisteredClient.Builder b = RegisteredClient.withId(id)
                .clientId(c.clientId())
                .clientName(c.name())
                .clientIdIssuedAt(existing != null ? existing.getClientIdIssuedAt() : Instant.now());
        if (c.isPublic()) {
            b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            b.clientSecret(encoder.encode(c.secret()))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        }
        c.grantTypes().forEach(g -> b.authorizationGrantType(new AuthorizationGrantType(g)));
        c.redirectUris().forEach(b::redirectUri);
        c.postLogoutRedirectUris().forEach(b::postLogoutRedirectUri);
        c.scopes().forEach(b::scope);
        b.clientSettings(ClientSettings.builder()
                .requireProofKey(c.isPublic() || c.grantTypes().contains("authorization_code"))
                .requireAuthorizationConsent(c.requireConsent())
                .setting(PublicClientRefreshTokenAuthenticationProvider.PUBLIC_REFRESH_TOKENS, c.publicRefreshTokens())
                .build());
        b.tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(c.accessTokenTtl())
                .refreshTokenTimeToLive(c.refreshTokenTtl())
                .reuseRefreshTokens(false)
                .build());
        return b.build();
    }
}
