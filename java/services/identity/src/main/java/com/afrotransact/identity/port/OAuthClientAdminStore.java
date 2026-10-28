package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.identity.domain.OAuthClient;

/** Registered OAuth clients for the admin console. Secrets only go in (as hashes), never out. */
public interface OAuthClientAdminStore {

    List<OAuthClient> all();

    Optional<OAuthClient> find(String clientId);

    /** @param secretHash null for a public client */
    void create(OAuthClient client, String secretHash, Instant at, String by);

    /** Saves every editable field (not the secret, not the enabled switch). */
    void update(OAuthClient client);

    void setEnabled(String clientId, boolean enabled, Instant at, String by);

    /**
     * Replaces the client's secret. The secret it had until now keeps working until {@code previousValidUntil}.
     */
    void replaceSecret(String clientId, String newSecretHash, Instant previousValidUntil, Instant at, String by);
}
