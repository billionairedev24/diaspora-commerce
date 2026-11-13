package com.afrotransact.identity.config;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.adapter.in.security.PublicClientRefreshTokenAuthenticationProvider;
import com.afrotransact.identity.adapter.out.crypto.Pem;
import com.afrotransact.identity.application.BootstrapService;
import com.afrotransact.identity.application.SigningKeyAdminService;
import com.afrotransact.identity.domain.ClientReconcileMode;
import com.afrotransact.identity.domain.LocalDevelopmentPolicy;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
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
 * ({@code at.identity.clients}) and the bootstrap super administrator. Clients are created when missing. Once a client
 * exists the staff console owns it (S291): by default start-up only reports differences in its scopes and redirect
 * URIs ({@code at.identity.provisioning.reconcile}: report, additive, sync; see {@link ClientReconciler}), and never
 * touches its secret unless {@code rotate-secrets} is on.
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
    private final LocalDevelopmentPolicy developmentPolicy;
    private final Environment environment;
    private final ClientReconciler reconciler;

    StartupProvisioning(IdentityProperties props, JdbcRegisteredClientRepository clients, PasswordEncoder encoder,
                        BootstrapService bootstrap, SigningKeyAdminService signingKeys,
                        ConfiguredSigningKeys configuredKeys, LocalDevelopmentPolicy developmentPolicy,
                        Environment environment, ClientReconciler reconciler) {
        this.reconciler = reconciler;
        this.developmentPolicy = developmentPolicy;
        this.environment = environment;
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
        int drifted = 0;
        int updated = 0;
        ClientReconcileMode mode = ClientReconcileMode.parse(props.provisioning().reconcile());
        Set<String> profiles = LocalDevelopmentConfiguration.activeProfiles(environment);
        for (IdentityProperties.Client c : props.clients()) {
            checkPublicClientScopes(profiles, c, props.provisioning().publicClientForbiddenScopes());
            if (!c.isPublic() && (c.secret() == null || c.secret().isBlank())) {
                LOG.warn("Skipping confidential client {}: no secret configured", c.clientId());
                continue;
            }
            if (clients.findByClientId(c.clientId()) == null) {
                clients.save(toRegisteredClient(c));
                created++;
            } else {
                ClientReconciler.Outcome outcome = reconciler.reconcile(c, mode, props.provisioning().rotateSecrets());
                drifted += outcome.drift() ? 1 : 0;
                updated += outcome.updated() ? 1 : 0;
            }
        }
        LOG.info("Created {} of {} configured OAuth clients; existing clients: {} differ from the configuration, {} "
                + "updated (reconcile mode {}; secrets are never touched unless rotate-secrets is on)", created,
                props.clients().size(), drifted, updated, mode);
        IdentityProperties.BootstrapAdmin admin = props.bootstrapAdmin();
        if (admin.email() != null && !admin.email().isBlank() && admin.password() != null
                && !admin.password().isBlank()) {
            LocalDevelopmentConfiguration.checkedBootstrapAdmin(developmentPolicy,
                    LocalDevelopmentConfiguration.activeProfiles(environment), admin.email(), admin.password());
            bootstrap.ensureSuperAdmin(admin.email(), admin.password(),
                    admin.firstName() == null ? "Platform" : admin.firstName(),
                    admin.lastName() == null ? "Administrator" : admin.lastName(), admin.phone());
        }
    }

    /**
     * A public client holds no secret, so anyone can start its authorization-code flow: on uat and prod it may not be
     * configured with a scope that reaches staff tools (default {@code mcp:staff}). Start-up fails instead.
     */
    static void checkPublicClientScopes(Set<String> profiles, IdentityProperties.Client client, List<String> forbidden) {
        if (!client.isPublic() || !(profiles.contains("prod") || profiles.contains("uat"))) {
            return;
        }
        for (String scope : client.scopes()) {
            if (forbidden.contains(scope)) {
                throw new IllegalStateException("Public OAuth client " + client.clientId() + " is configured with scope "
                        + scope + ", which public clients may not have on uat and prod (active profiles " + profiles
                        + "); give it to a confidential client");
            }
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
