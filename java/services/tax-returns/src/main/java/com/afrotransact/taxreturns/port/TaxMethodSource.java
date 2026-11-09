package com.afrotransact.taxreturns.port;

import java.util.Optional;

import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/** Which method works out sales tax right now (what staff chose in Settings, else the configured default). */
public interface TaxMethodSource {

    TaxMethod method();

    /** The provider quoted next to the in-house engine (SHADOW mode), empty when off. */
    Optional<TaxProviderId> shadowProvider();
}
