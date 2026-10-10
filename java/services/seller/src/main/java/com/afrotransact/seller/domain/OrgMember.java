package com.afrotransact.seller.domain;

import java.time.Instant;

/**
 * A user (identity-service id) and their organisation role key, e.g. {@code seller.owner}. Identity is the source of
 * truth for members: this row is created for the applicant at onboarding and then kept in step with identity's
 * {@code identity.role.granted / revoked} events (S236).
 *
 * @param updatedAt when the role was last set (orders identity changes)
 */
public record OrgMember(String orgId, String userId, String roleKey, Instant createdAt, Instant updatedAt) {

    public OrgMember(String orgId, String userId, String roleKey, Instant createdAt) {
        this(orgId, userId, roleKey, createdAt, createdAt);
    }

    public boolean isOwner() {
        return roleKey != null && roleKey.endsWith(".owner");
    }

    /** Organisation role keys are {@code seller.*} or {@code provider.*}. */
    public static boolean isOrgRole(String roleKey) {
        return roleKey != null && (roleKey.startsWith("seller.") || roleKey.startsWith("provider."))
                && !roleKey.endsWith(".applicant");
    }
}
