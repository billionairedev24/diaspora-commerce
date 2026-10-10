package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.OrgMember;
import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.Organization;

/** Seller/provider organisations and memberships (ids are the seller service's {@code org_…} strings). */
public interface OrganizationRepository {

    Organization upsert(Organization org);

    Optional<Organization> findById(String id);

    List<OrgMembership> memberships(UUID userId);

    /** @return true when the membership was created or its role changed */
    boolean setMember(String orgId, UUID userId, String roleKey);

    boolean removeMember(String orgId, UUID userId);

    /** The member's organisation role, if the user is a member. */
    Optional<String> memberRole(String orgId, UUID userId);

    /** Member directory: every member with name, email, role and join date, ordered by name. */
    List<OrgMember> members(String orgId);

    /** Members with an owner role ({@code seller.owner} / {@code provider.owner}) whose accounts are enabled. */
    List<UUID> ownerUserIds(String orgId);
}
