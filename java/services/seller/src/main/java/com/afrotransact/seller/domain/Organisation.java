package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A seller or service-provider organisation.
 *
 * @param id          {@code org_…}
 * @param kind        onboarding portal: SELLER or PROVIDER (role-key prefix, plans)
 * @param displayName store / public name shown to shoppers
 * @param offering    what it sells (products, services or both), from the approved application; null before
 */
public record Organisation(String id, OrgKind kind, String legalName, String displayName, String country,
                           Offering offering, OrgStatus status, Instant createdAt, Instant updatedAt, long version) {

    /** Kind on seller.org.* events when the organisation offers products and services (S215). */
    public static final String KIND_BOTH = "BOTH";

    public Organisation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
    }

    public static Organisation draft(String id, OrgKind kind, String displayName, Instant now) {
        return new Organisation(id, kind, null, displayName, null, null, OrgStatus.DRAFT, now, now, 0);
    }

    public Organisation withStatus(OrgStatus next, Instant now) {
        return new Organisation(id, kind, legalName, displayName, country, offering, next, createdAt, now, version);
    }

    public Organisation withProfile(String newLegalName, String newDisplayName, String newCountry, Instant now) {
        return new Organisation(id, kind, newLegalName, newDisplayName, newCountry, offering, status, createdAt, now,
                version);
    }

    public Organisation withOffering(Offering next, Instant now) {
        return new Organisation(id, kind, legalName, displayName, country, next, status, createdAt, now, version);
    }

    public boolean isApproved() {
        return status == OrgStatus.APPROVED;
    }

    /**
     * Kind published to other services: {@code BOTH} when the organisation offers products and services, else
     * SELLER or PROVIDER.
     */
    public String eventKind() {
        return offering == Offering.BOTH ? KIND_BOTH : kind.name();
    }
}
