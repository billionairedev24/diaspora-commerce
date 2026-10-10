package com.afrotransact.identity.domain;

import java.util.UUID;

/** A user's role inside one organisation, for example {@code seller.owner} in "Mama Tee's Pantry". */
public record OrgMembership(UUID orgId, OrgKind kind, String orgName, OrgStatus status, String roleKey) {

    public boolean approved() {
        return status == OrgStatus.APPROVED;
    }
}
