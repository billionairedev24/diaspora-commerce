package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.identity.domain.AdminReason;
import com.afrotransact.identity.domain.ClientSecrets;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.OAuthClient;
import com.afrotransact.identity.domain.OAuthClientRules;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OAuthClientAdminStore;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OAuthClientAdminServiceImpl implements OAuthClientAdminService {

    private final OAuthClientAdminStore clients;
    private final PasswordHasher hasher;
    private final PermissionChecker permissions;
    private final AccessChangeLog changeLog;
    private final ProtectedClients protectedClients;
    private final Clock clock;

    OAuthClientAdminServiceImpl(OAuthClientAdminStore clients, PasswordHasher hasher, PermissionChecker permissions,
                                AccessChangeLog changeLog, ProtectedClients protectedClients, Clock clock) {
        this.clients = clients;
        this.hasher = hasher;
        this.permissions = permissions;
        this.changeLog = changeLog;
        this.protectedClients = protectedClients;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OAuthClient> list() {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        return clients.all();
    }

    @Override
    @Transactional(readOnly = true)
    public OAuthClient get(String clientId) {
        permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        return find(clientId);
    }

    @Override
    @Transactional
    public ClientWithSecret create(ClientDraft d, String clientId, boolean confidential, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        OAuthClientRules.validate(clientId, d.name(), confidential, d.grantTypes(), d.scopes(), d.redirectUris(),
                d.postLogoutRedirectUris(), d.accessTokenTtl(), d.refreshTokenTtl());
        if (clients.find(clientId).isPresent()) {
            throw new ConflictException("identity.client-exists", "A client with the id " + clientId + " exists");
        }
        Instant now = Instant.now(clock);
        OAuthClient client = build(clientId, confidential, d, true, now, null, null);
        String secret = confidential ? ClientSecrets.generate() : null;
        clients.create(client, secret == null ? null : hasher.hash(secret), now, actor.subject());
        changeLog.append(actor.subject(), "oauth-client.created", null, null, clientId,
                "reason=" + why + "; after=" + client.auditSummary());
        return new ClientWithSecret(client, secret);
    }

    @Override
    @Transactional
    public OAuthClient update(String clientId, ClientDraft d, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        OAuthClient before = find(clientId);
        OAuthClientRules.validate(null, d.name(), before.confidential(), d.grantTypes(), d.scopes(), d.redirectUris(),
                d.postLogoutRedirectUris(), d.accessTokenTtl(), d.refreshTokenTtl());
        OAuthClient after = build(clientId, before.confidential(), d, before.enabled(), before.issuedAt(),
                before.secretRotatedAt(), before.previousSecretExpiresAt());
        clients.update(after);
        changeLog.append(actor.subject(), "oauth-client.updated", null, null, clientId,
                "reason=" + why + "; before=" + before.auditSummary() + "; after=" + after.auditSummary());
        return find(clientId);
    }

    @Override
    @Transactional
    public OAuthClient setEnabled(String clientId, boolean enabled, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        OAuthClient before = find(clientId);
        if (!enabled && protectedClients.contains(clientId)) {
            throw new IdentityRuleViolation("identity.client-protected",
                    clientId + " is the platform's own sign-in client; disabling it would lock every staff member out",
                    Map.of("clientId", "This client cannot be disabled here"));
        }
        if (before.enabled() != enabled) {
            clients.setEnabled(clientId, enabled, Instant.now(clock), actor.subject());
            changeLog.append(actor.subject(), enabled ? "oauth-client.enabled" : "oauth-client.disabled", null, null,
                    clientId, "reason=" + why + "; before enabled=" + before.enabled() + "; after enabled=" + enabled);
        }
        return find(clientId);
    }

    @Override
    @Transactional
    public ClientWithSecret rotateSecret(String clientId, String reason) {
        Actor actor = permissions.require(IdentityPermissions.IDENTITY_ADMIN);
        String why = AdminReason.require(reason);
        OAuthClient before = find(clientId);
        if (!before.confidential()) {
            throw new IdentityRuleViolation("identity.client-public",
                    "A public client has no secret to rotate", Map.of("clientId", "Public clients have no secret"));
        }
        Instant now = Instant.now(clock);
        String secret = ClientSecrets.generate();
        Instant graceEnds = now.plus(ClientSecrets.ROTATION_GRACE);
        clients.replaceSecret(clientId, hasher.hash(secret), graceEnds, now, actor.subject());
        changeLog.append(actor.subject(), "oauth-client.secret-rotated", null, null, clientId,
                "reason=" + why + "; previous secret valid until " + graceEnds);
        return new ClientWithSecret(find(clientId), secret);
    }

    private OAuthClient find(String clientId) {
        return clients.find(clientId).orElseThrow(() -> new NotFoundException("identity.client-not-found",
                "Unknown OAuth client " + clientId));
    }

    private static OAuthClient build(String clientId, boolean confidential, ClientDraft d, boolean enabled,
                                     Instant issuedAt, Instant rotatedAt, Instant previousUntil) {
        boolean pkce = !confidential || d.grantTypes().contains("authorization_code");
        return new OAuthClient(clientId, d.name().trim(), confidential, d.grantTypes(), d.scopes(), d.redirectUris(),
                d.postLogoutRedirectUris(), pkce, d.requireConsent(), d.publicRefreshTokens() && !confidential,
                d.accessTokenTtl(), d.refreshTokenTtl(), enabled, issuedAt, rotatedAt, previousUntil);
    }
}
