package com.afrotransact.seller.adapter.in.module;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.module.InProcessProvider;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.api.SellerLookupApi;
import com.afrotransact.seller.application.EntitlementsService;
import com.afrotransact.seller.application.SellingStatusService;
import com.afrotransact.seller.application.ShipFromAddressService;
import com.afrotransact.seller.domain.ShipFromAddress;
import com.afrotransact.seller.domain.SellingFacts;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link SellerLookupApi}: the use cases behind {@code GET /v1/orgs/{orgId}/selling-status},
 * {@code GET /v1/orgs/{orgId}/entitlements} and {@code GET /v1/ship-from-locations/{id}}, entered under the calling module's
 * principal. It adds no rule and no permission of its own: the application services require the service scope exactly as they do
 * for a token. It refuses to run without a caller, and when the actor in force is not that caller.
 */
@Component
class SellerLookupApiAdapter implements SellerLookupApi {

    private final SellingStatusService selling;
    private final EntitlementsService entitlements;
    private final ShipFromAddressService addresses;
    private final ActorResolver actors;

    SellerLookupApiAdapter(SellingStatusService selling, EntitlementsService entitlements,
                           ShipFromAddressService addresses, ActorResolver actors) {
        this.selling = selling;
        this.entitlements = entitlements;
        this.addresses = addresses;
        this.actors = actors;
    }

    @Override
    public SellingStatus sellingStatus(String orgId, String categoryCodes, String brand, String productTypeCode,
                                       String material, String gtin) {
        InProcessProvider.requireActing(actors.current().subject());
        var status = selling.status(orgId, new SellingFacts(categoryCodes, brand, productTypeCode, material, gtin, false));
        return new SellingStatus(status.level().name(), status.reason(), status.missing().stream()
                .map(m -> new Missing(m.scope().type().name(), m.scope().key(), m.label(), m.level().name(),
                        m.state().name(), m.message(), m.canApply())).toList(), status.pendingFacts());
    }

    @Override
    public ListingEntitlements listingEntitlements(String orgId) {
        InProcessProvider.requireActing(actors.current().subject());
        var e = entitlements.forOrg(orgId);
        return new ListingEntitlements(e.planName(), e.plan().listings(), e.plan().listings() == null);
    }

    @Override
    public Optional<ShipFromLocation> shipFromLocation(String locationId) {
        InProcessProvider.requireActing(actors.current().subject());
        try {
            ShipFromAddress a = addresses.location(UUID.fromString(locationId));
            return Optional.of(new ShipFromLocation(a.id().toString(), a.orgId(), a.city(), a.state(), a.country(),
                    a.postalCode(), a.pickup().enabled()));
        } catch (IllegalArgumentException | NotFoundException ex) {
            return Optional.empty();
        }
    }
}
