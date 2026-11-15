package com.afrotransact.seller.api;

import java.util.List;
import java.util.Optional;

/**
 * What other modules may ask seller in-process, beyond {@link OrgMembershipApi}: the reads catalog and offers make over HTTP
 * today. Each method is the use case behind one endpoint, entered under the calling module's principal; the endpoint's scope is
 * the method's scope and a caller without it is refused with the platform's {@code ForbiddenActionException} (HTTP 403).
 */
public interface SellerLookupApi {

    /**
     * {@code GET /v1/orgs/{orgId}/selling-status} (scope {@code sellers:catalog-scope.read}): can the organisation sell a product
     * with these facts. Null facts are not known yet.
     */
    SellingStatus sellingStatus(String orgId, String categoryCodes, String brand, String productTypeCode, String material,
                                String gtin);

    /** {@code GET /v1/orgs/{orgId}/entitlements} (scope {@code sellers:entitlements.read}): the live-listing cap of the plan. */
    ListingEntitlements listingEntitlements(String orgId);

    /**
     * {@code GET /v1/ship-from-locations/{id}} (scope {@code sellers:ship-from.read}): empty when there is no such location or the
     * id is not a location id (HTTP 404 or 400).
     */
    Optional<ShipFromLocation> shipFromLocation(String locationId);

    /** @param level SELL, APPLY or NOT_AVAILABLE */
    record SellingStatus(String level, String reason, List<Missing> missing, List<String> pendingFacts) {

        public SellingStatus {
            missing = missing == null ? List.of() : List.copyOf(missing);
            pendingFacts = pendingFacts == null ? List.of() : List.copyOf(pendingFacts);
        }
    }

    /** One scope in the way; {@code state} is NOT_APPLIED, APPLIED, NEEDS_ATTENTION, EXPIRED or NOT_AVAILABLE. */
    record Missing(String scopeType, String scopeKey, String label, String level, String state, String message,
                   boolean canApply) {
    }

    /**
     * @param planName the plan's name, null when the organisation has none
     * @param listings the most live listings; null = unlimited
     */
    record ListingEntitlements(String planName, Long listings, boolean listingsUnlimited) {
    }

    record ShipFromLocation(String id, String orgId, String city, String state, String country, String postalCode,
                            boolean pickupEnabled) {
    }
}
