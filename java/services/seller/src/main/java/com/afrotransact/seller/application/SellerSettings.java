package com.afrotransact.seller.application;

import java.util.List;
import java.util.Set;

/**
 * Runtime settings used by the use cases.
 *
 * @param liveCountries     ISO codes of markets open to sellers (US only by default)
 * @param sessionComponents Stripe embedded components enabled in Account Sessions
 */
public record SellerSettings(Set<String> liveCountries, List<String> sessionComponents) {

    public SellerSettings {
        liveCountries = Set.copyOf(liveCountries);
        sessionComponents = List.copyOf(sessionComponents);
    }
}
