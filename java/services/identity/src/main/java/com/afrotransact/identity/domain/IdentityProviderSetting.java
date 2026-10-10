package com.afrotransact.identity.domain;

import java.time.Instant;

/** Whether a social provider appears on sign-in screens (Developers › Identity providers). */
public record IdentityProviderSetting(String id, String displayName, boolean enabled, int position,
                                      Instant updatedAt, String updatedBy) {

    public IdentityProviderSetting withEnabled(boolean value, Instant now, String actor) {
        return new IdentityProviderSetting(id, displayName, value, position, now, actor);
    }
}
