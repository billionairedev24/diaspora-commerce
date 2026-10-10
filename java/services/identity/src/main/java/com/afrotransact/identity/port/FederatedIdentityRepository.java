package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.FederatedIdentity;

/** Social identities linked to accounts. */
public interface FederatedIdentityRepository {

    Optional<FederatedIdentity> find(String provider, String subject);

    List<FederatedIdentity> forUser(UUID userId);

    void link(FederatedIdentity identity);

    boolean unlink(UUID userId, String provider);
}
