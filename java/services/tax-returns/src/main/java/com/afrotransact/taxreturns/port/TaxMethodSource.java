package com.afrotransact.taxreturns.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/** Which method works out sales tax right now (what staff chose in Settings, else the configured default). */
public interface TaxMethodSource {

    TaxMethod method();

    /** The provider quoted next to the in-house engine (SHADOW mode), empty when off. */
    Optional<TaxProviderId> shadowProvider();

    /**
     * Every provider quoted next to the in-house engine: when SHADOW mode is on, each configured provider other than
     * the one charging (empty when off). Defaults to the single {@link #shadowProvider()}.
     */
    default List<TaxProviderId> shadowProviders() {
        return shadowProvider().map(List::of).orElse(List.of());
    }
}
