package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;

/**
 * Seller/provider organisations mirrored from the seller service (requires {@code orgs:manage}, typically a
 * client-credentials token of the seller service). Approval status drives landing and org-role permissions.
 * Members are managed by {@link OrgTeamService}.
 */
public interface OrganizationService {

    /** {@code orgId} is the seller service's id ({@code org_…}); it becomes the token {@code org} claim. */
    Organization upsert(String orgId, OrgKind kind, String name, OrgStatus status);
}
