package com.afrotransact.seller.domain;

import java.time.Instant;

/** A user (identity-service id) and their organisation role key, e.g. {@code seller.owner}. */
public record OrgMember(String orgId, String userId, String roleKey, Instant createdAt) {

    public boolean isOwner() {
        return roleKey != null && roleKey.endsWith(".owner");
    }
}
