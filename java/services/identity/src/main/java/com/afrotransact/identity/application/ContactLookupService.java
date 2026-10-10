package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.identity.domain.PushApp;

/** Service-to-service lookups (SERVICE principals with {@code users:read}), used by notifications-service. */
public interface ContactLookupService {

    /** @param app only push tokens of this app, or null for all */
    ContactView contact(UUID userId, PushApp app);

    OrgOwnersView orgOwners(String orgId);
}
