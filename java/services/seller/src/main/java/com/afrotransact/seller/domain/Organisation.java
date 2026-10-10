package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A seller or service-provider organisation.
 *
 * @param id          {@code org_…}
 * @param displayName store / public name shown to shoppers
 */
public record Organisation(String id, OrgKind kind, String legalName, String displayName, String country,
                           OrgStatus status, Instant createdAt, Instant updatedAt, long version) {

    public Organisation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
    }

    public static Organisation draft(String id, OrgKind kind, String displayName, Instant now) {
        return new Organisation(id, kind, null, displayName, null, OrgStatus.DRAFT, now, now, 0);
    }

    public Organisation withStatus(OrgStatus next, Instant now) {
        return new Organisation(id, kind, legalName, displayName, country, next, createdAt, now, version);
    }

    public Organisation withProfile(String newLegalName, String newDisplayName, String newCountry, Instant now) {
        return new Organisation(id, kind, newLegalName, newDisplayName, newCountry, status, createdAt, now, version);
    }

    public boolean isApproved() {
        return status == OrgStatus.APPROVED;
    }
}
