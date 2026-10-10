package com.afrotransact.identity.domain;

import java.time.Duration;

/**
 * Organisation team rules: which roles fit an organisation, who must stay an owner, and invitation tokens.
 */
public interface TeamPolicy {

    /** Invitations are good for 7 days. */
    Duration INVITATION_TTL = Duration.ofDays(7);

    /** Random bytes in an invitation token (256 bits). */
    int TOKEN_BYTES = 32;

    /** The role portal of an organisation kind: SELLER → seller.*, PROVIDER → provider.*. */
    Portal portalOf(OrgKind kind);

    /** Key of the "Team &amp; roles" module in the kind's module access matrix ({@code sTeam} / {@code pTeam}). */
    String teamModuleKey(OrgKind kind);

    boolean isOwnerRole(String roleKey);

    /** Throws {@link IdentityRuleViolation} ({@code identity.wrong-portal}) unless the role fits the organisation. */
    void requireRoleFor(OrgKind kind, Role role);

    /**
     * Throws {@link IdentityRuleViolation} ({@code identity.last-owner}) when a change would leave the organisation
     * without an enabled owner.
     *
     * @param currentRole the member's role now
     * @param newRole     the role after the change, or null when the member is removed
     * @param otherOwners enabled owners other than this member
     */
    void requireOwnerRemains(String currentRole, String newRole, int otherOwners);

    /** A fresh URL-safe single-use invitation token. */
    String newToken();

    /** SHA-256 (hex) of a token; the only form that is stored. */
    String hashToken(String token);
}
