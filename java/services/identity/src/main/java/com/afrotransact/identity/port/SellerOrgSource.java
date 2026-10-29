package com.afrotransact.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The seller service's current state of its organisations (GET /v1/orgs/mirror, /v1/orgs/{id}/mirror), the source of
 * truth identity's copy is reconciled with.
 */
public interface SellerOrgSource {

    /** Organisations the seller service approved or suspended (those that have members), ordered by id. */
    SellerOrgPage approvedOrSuspended(int page, int size);

    /** One organisation, or empty when the seller service does not know it. */
    Optional<SellerOrgState> org(String orgId);

    /** The same fields as seller.org.approved / seller.org.updated; {@code updatedAt} is the seller's last change. */
    record SellerOrgState(String orgId, String kind, String primaryKind, String legalName, String displayName,
                          String country, String status, List<String> ownerUserIds, String applicantUserId,
                          Instant updatedAt) {

        public SellerOrgState {
            ownerUserIds = ownerUserIds == null ? List.of() : List.copyOf(ownerUserIds);
        }
    }

    record SellerOrgPage(List<SellerOrgState> items, long total) {

        public SellerOrgPage {
            items = List.copyOf(items);
        }
    }
}
