package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.OrgMembership;
import com.afrotransact.identity.domain.Organization;

/** Seller/provider organisations and memberships. */
public interface OrganizationRepository {

    Organization upsert(Organization org);

    Optional<Organization> findById(UUID id);

    List<OrgMembership> memberships(UUID userId);

    /** @return true when the membership was created or its role changed */
    boolean setMember(UUID orgId, UUID userId, String roleKey);

    boolean removeMember(UUID orgId, UUID userId);
}
