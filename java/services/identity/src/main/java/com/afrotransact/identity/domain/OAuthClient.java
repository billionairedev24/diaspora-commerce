package com.afrotransact.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A registered OAuth client as the admin console shows and edits it. The secret is never part of this record.
 *
 * @param confidential            true when the client authenticates with a secret (web BFF, service accounts)
 * @param secretRotatedAt         when the secret was last rotated here (null if never)
 * @param previousSecretExpiresAt until when the previous secret still works (null when no rotation is in grace)
 */
public record OAuthClient(String clientId, String name, boolean confidential, List<String> grantTypes,
                          List<String> scopes, List<String> redirectUris, List<String> postLogoutRedirectUris,
                          boolean requirePkce, boolean requireConsent, boolean publicRefreshTokens,
                          Duration accessTokenTtl, Duration refreshTokenTtl, boolean enabled, Instant issuedAt,
                          Instant secretRotatedAt, Instant previousSecretExpiresAt) {

    public OAuthClient {
        grantTypes = List.copyOf(grantTypes);
        scopes = List.copyOf(scopes);
        redirectUris = List.copyOf(redirectUris);
        postLogoutRedirectUris = List.copyOf(postLogoutRedirectUris);
    }

    public String type() {
        return confidential ? "confidential" : "public";
    }

    /** One line for the audit log: the fields an admin can change. */
    public String auditSummary() {
        return "name=" + name + " grants=" + grantTypes + " scopes=" + scopes + " redirects=" + redirectUris.size()
                + " pkce=" + requirePkce + " consent=" + requireConsent + " refreshPublic=" + publicRefreshTokens
                + " access=" + accessTokenTtl + " refresh=" + refreshTokenTtl + " enabled=" + enabled;
    }
}
