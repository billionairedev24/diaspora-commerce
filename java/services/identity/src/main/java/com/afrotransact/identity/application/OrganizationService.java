package com.afrotransact.identity.application;

import java.util.UUID;

import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;

/**
 * Seller/provider organisations mirrored from the seller service (requires {@code orgs:manage}, typically a
 * client-credentials token of the seller service). Approval status drives landing and org-role permissions.
 */
public interface OrganizationService {

    Organization upsert(UUID orgId, OrgKind kind, String name, OrgStatus status);

    /** Adds the user to the organisation with a seller.* / provider.* role (or changes their role). */
    void setMember(UUID orgId, UUID userId, String roleKey);

    void removeMember(UUID orgId, UUID userId);
}
