package com.afrotransact.identity.application;

import java.time.Duration;
import java.util.List;

import com.afrotransact.identity.domain.OAuthClient;

/**
 * Registered OAuth clients, managed from the staff console (S291). Every method needs {@code identity:admin}; changes
 * need a reason and are written to the access change log. Secrets are generated here, shown once and stored hashed.
 */
public interface OAuthClientAdminService {

    List<OAuthClient> list();

    OAuthClient get(String clientId);

    /** A confidential client's secret is in the result and nowhere else. */
    ClientWithSecret create(ClientDraft draft, String clientId, boolean confidential, String reason);

    /** Edits everything but the id and the type. */
    OAuthClient update(String clientId, ClientDraft draft, String reason);

    /** A disabled client is refused at the token endpoint; its refresh tokens stop working. */
    OAuthClient setEnabled(String clientId, boolean enabled, String reason);

    /** New secret, shown once; the old one keeps working for {@code ClientSecrets.ROTATION_GRACE}. */
    ClientWithSecret rotateSecret(String clientId, String reason);

    /** The editable fields of a client. */
    record ClientDraft(String name, List<String> grantTypes, List<String> scopes, List<String> redirectUris,
                       List<String> postLogoutRedirectUris, boolean requireConsent, boolean publicRefreshTokens,
                       Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    /** @param secret the new secret in clear (null for a public client) */
    record ClientWithSecret(OAuthClient client, String secret) {

        /** Never prints the secret. */
        @Override
        public String toString() {
            return "ClientWithSecret[client=" + client.clientId() + ", secret=<hidden>]";
        }
    }
}
