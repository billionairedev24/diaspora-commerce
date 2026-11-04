package com.afrotransact.identity.config;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.security.PublicClientRefreshTokenAuthenticationProvider;
import com.afrotransact.identity.adapter.out.crypto.Pem;
import com.afrotransact.identity.application.BootstrapService;
import com.afrotransact.identity.application.SigningKeyAdminService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * Seeds the configured signing key (when no key is stored yet), the OAuth clients configured for this environment
 * ({@code at.identity.clients}) and the bootstrap super administrator. Clients are only created when missing: once a
 * client exists the staff console owns it (S291), so an edit made there is never overwritten by a deploy.
 */
@Component
class StartupProvisioning implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StartupProvisioning.class);

    private final IdentityProperties props;
    private final JdbcRegisteredClientRepository clients;
    private final PasswordEncoder encoder;
    private final BootstrapService bootstrap;
    private final SigningKeyAdminService signingKeys;
    private final ConfiguredSigningKeys configuredKeys;

    StartupProvisioning(IdentityProperties props, JdbcRegisteredClientRepository clients, PasswordEncoder encoder,
                        BootstrapService bootstrap, SigningKeyAdminService signingKeys,
                        ConfiguredSigningKeys configuredKeys) {
        this.props = props;
        this.clients = clients;
        this.encoder = encoder;
        this.bootstrap = bootstrap;
        this.signingKeys = signingKeys;
        this.configuredKeys = configuredKeys;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (configuredKeys.keys().get(0) instanceof RSAKey signing) {
            try {
                signingKeys.seedIfEmpty(signing.getKeyID(), Pem.publicKey(signing.toRSAPublicKey()),
                        Pem.privateKey(signing.toPrivateKey()));
            } catch (JOSEException ex) {
                throw new IllegalStateException("The configured signing key cannot be read", ex);
            }
        }
        int created = 0;
        for (IdentityProperties.Client c : props.clients()) {
            if (!c.isPublic() && (c.secret() == null || c.secret().isBlank())) {
                LOG.warn("Skipping confidential client {}: no secret configured", c.clientId());
                continue;
            }
            if (clients.findByClientId(c.clientId()) == null) {
                clients.save(toRegisteredClient(c));
                created++;
            }
        }
        LOG.info("Created {} of {} configured OAuth clients (existing clients are left as they are)", created,
                props.clients().size());
        IdentityProperties.BootstrapAdmin admin = props.bootstrapAdmin();
        if (admin.email() != null && !admin.email().isBlank() && admin.password() != null
                && !admin.password().isBlank()) {
            bootstrap.ensureSuperAdmin(admin.email(), admin.password(),
                    admin.firstName() == null ? "Platform" : admin.firstName(),
                    admin.lastName() == null ? "Administrator" : admin.lastName(), admin.phone());
        }
    }

    private RegisteredClient toRegisteredClient(IdentityProperties.Client c) {
        RegisteredClient.Builder b = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(c.clientId())
                .clientName(c.name())
                .clientIdIssuedAt(Instant.now());
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
