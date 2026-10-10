package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of an organisation's member directory.
 *
 * @param roleName display name of the organisation role, for example {@code Admin}
 * @param enabled  false when the account was disabled by an access administrator
 * @param joinedAt when the membership was created
 */
public record OrgMember(UUID userId, String firstName, String lastName, String email, String roleKey,
                        String roleName, boolean enabled, Instant joinedAt) {

    public String displayName() {
        return (firstName + " " + lastName).trim();
    }
}
