package com.afrotransact.identity.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.identity.domain.OrgMember;

/**
 * An organisation's team (S231): the member directory, role changes and removals. Called by the seller service
 * ({@code orgs:manage}, mirroring its members), staff, and members whose organisation role has {@code team:manage}
 * (Seller Center through gateway-bff). Roles must fit the organisation (seller.* / provider.*), only owners may
 * grant or change the owner role, and an organisation never loses its last enabled owner.
 */
public interface OrgTeamService {

    /** Members with names, email, role, status and join date ({@code team:manage}, team-module VIEW, or staff). */
    List<OrgMember> members(String orgId);

    /**
     * Roles that can be granted or invited in this organisation, for Seller Center's role pickers (S245): seller roles
     * for SELLER, provider roles for PROVIDER, both sets for BOTH. Same readers as {@link #members}.
     */
    AssignableRoles assignableRoles(String orgId);

    /**
     * Changes a member's role. The seller service and staff ({@code orgs:manage}) may also add a member directly;
     * members with {@code team:manage} invite instead and cannot change their own role.
     */
    void setMember(String orgId, UUID userId, String roleKey);

    /** Removes a member (idempotent for the seller service). */
    void removeMember(String orgId, UUID userId);
}
