package com.afrotransact.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A {@code seller.org.approved} or {@code seller.org.updated} event as identity needs it.
 *
 * @param eventId         the event-id header; the inbox de-duplicates on it
 * @param orgId           seller-service organisation id ({@code org_…}), the token {@code org} claim
 * @param kind            SELLER or PROVIDER
 * @param status          seller-service organisation status (APPROVED, SUSPENDED, …)
 * @param ownerUserIds    identity user ids of the organisation's owners
 * @param applicantUserId identity user id of the person whose application was approved (null on plain updates)
 */
public record SellerOrgChange(UUID eventId, String orgId, String kind, String legalName, String displayName,
                              String country, String status, List<String> ownerUserIds, String applicantUserId,
                              Instant occurredAt) {

    public SellerOrgChange {
        ownerUserIds = ownerUserIds == null ? List.of() : List.copyOf(ownerUserIds);
    }
}
