package com.afrotransact.identity.config;

import java.util.LinkedHashSet;
import java.util.Set;

import com.afrotransact.identity.domain.ClientReconcileMode;
import com.afrotransact.identity.domain.ClientReconcilePlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Component;

@Component
class ClientReconcilerImpl implements ClientReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(ClientReconcilerImpl.class);

    private final JdbcRegisteredClientRepository clients;
    private final PasswordEncoder encoder;

    ClientReconcilerImpl(JdbcRegisteredClientRepository clients, PasswordEncoder encoder) {
        this.clients = clients;
        this.encoder = encoder;
    }

    @Override
    public Outcome reconcile(IdentityProperties.Client configured, ClientReconcileMode mode, boolean rotateSecret) {
        RegisteredClient stored = clients.findByClientId(configured.clientId());
        if (stored == null) {
            return Outcome.none();
        }
        ClientReconcilePlan plan = ClientReconcilePlan.between(new LinkedHashSet<>(configured.scopes()),
                stored.getScopes(), new LinkedHashSet<>(configured.redirectUris()), stored.getRedirectUris(),
                new LinkedHashSet<>(configured.postLogoutRedirectUris()), stored.getPostLogoutRedirectUris());
        boolean rotate = rotateSecret && !configured.isPublic() && configured.secret() != null
                && !configured.secret().isBlank() && stored.getClientSecret() != null
                && !encoder.matches(configured.secret(), stored.getClientSecret());
        if (plan.inSync() && !rotate) {
            return Outcome.none();
        }
        if (!plan.inSync()) {
            LOG.warn("audit: identity.client.drift client={} mode={} scopes +{} -{} redirectUris +{} -{} "
                            + "postLogoutUris +{} -{}", configured.clientId(), mode, plan.addedScopes(),
                    plan.removedScopes(), plan.addedRedirectUris(), plan.removedRedirectUris(),
                    plan.addedPostLogoutUris(), plan.removedPostLogoutUris());
        }
        boolean change = plan.changes(mode);
        if (!change && !rotate) {
            return new Outcome(true, false, false);
        }
        RegisteredClient.Builder b = RegisteredClient.from(stored);
        if (change) {
            Set<String> scopes = plan.resultingScopes(mode, stored.getScopes());
            Set<String> redirects = plan.resultingRedirectUris(mode, stored.getRedirectUris());
            Set<String> postLogout = plan.resultingPostLogoutUris(mode, stored.getPostLogoutRedirectUris());
            b.scopes(s -> {
                s.clear();
                s.addAll(scopes);
            });
            b.redirectUris(s -> {
                s.clear();
                s.addAll(redirects);
            });
            b.postLogoutRedirectUris(s -> {
                s.clear();
                s.addAll(postLogout);
            });
        }
        if (rotate) {
            b.clientSecret(encoder.encode(configured.secret()));
        }
        clients.save(b.build());
        LOG.warn("audit: identity.client.reconciled client={} mode={} scopesAdded={} scopesRemoved={} redirectsAdded={} "
                        + "redirectsRemoved={} secretRotated={}", configured.clientId(), mode,
                change ? plan.addedScopes() : Set.of(), change && mode == ClientReconcileMode.SYNC ? plan.removedScopes() : Set.of(),
                change ? plan.addedRedirectUris() : Set.of(),
                change && mode == ClientReconcileMode.SYNC ? plan.removedRedirectUris() : Set.of(), rotate);
        return new Outcome(!plan.inSync(), true, rotate);
    }
}
