package com.afrotransact.identity.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.PushApp;

/** Service-to-service lookups (SERVICE principals with {@code users:read}), used by notifications-service. */
public interface ContactLookupService {

    /** @param app only push tokens of this app, or null for all */
    ContactView contact(UUID userId, PushApp app);

    OrgOwnersView orgOwners(String orgId);

    /** Enabled staff holding a permission (or, when {@code role} is set, a role; or, with {@code ids}, just those enabled accounts): the application review roster. */
    List<StaffEntry> staff(String permission, String role, List<String> ids);

    record StaffEntry(String userId, String name, String email) {
    }
}
