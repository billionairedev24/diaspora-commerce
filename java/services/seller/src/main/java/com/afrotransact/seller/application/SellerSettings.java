package com.afrotransact.seller.application;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime settings used by the use cases.
 *
 * @param liveCountries     ISO codes of markets open to sellers (US only by default)
 * @param sessionComponents Stripe embedded components enabled in Account Sessions
 * @param retailOrgId       AfroTransact Retail, the first-party merchant of record: not a marketplace seller, so it
 *                          is never on a plan, never metered (storage, ads, AI credits) and never suspended
 * @param pickupMaxRadiusMiles AfroTransact pickup (S255) is allowed inside an active DELIVERY zone or at most this many
 *                          miles from one
 */
public record SellerSettings(Set<String> liveCountries, List<String> sessionComponents, String retailOrgId,
                             double pickupMaxRadiusMiles) {

    public SellerSettings {
        liveCountries = Set.copyOf(liveCountries);
        sessionComponents = List.copyOf(sessionComponents);
        Objects.requireNonNull(retailOrgId, "retailOrgId");
    }

    /** Settings with the default pickup radius (25 miles). */
    public SellerSettings(Set<String> liveCountries, List<String> sessionComponents, String retailOrgId) {
        this(liveCountries, sessionComponents, retailOrgId, 25);
    }

    /** True for AfroTransact Retail, which seller fees, plans, metering and enforcement never apply to. */
    public boolean isRetail(String orgId) {
        return retailOrgId.equals(orgId);
    }
}
