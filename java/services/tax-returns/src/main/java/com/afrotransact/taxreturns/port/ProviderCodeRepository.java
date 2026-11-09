package com.afrotransact.taxreturns.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/** The internal outcome code to provider code map. */
public interface ProviderCodeRepository {

    List<ProviderCode> list(TaxProviderId provider, String categoryCode, boolean unmappedOnly);

    Optional<ProviderCode> find(String internalTaxCode, TaxProviderId provider);

    /** Every provider's entry for these outcome codes. */
    List<ProviderCode> forCodes(Collection<String> internalTaxCodes);

    /** Entries of the provider that carry a code (mapped), regardless of verification. */
    long countMapped(TaxProviderId provider);

    /** Replaces the entry's code, flags and note and counts a new map version; false when the outcome code is unknown. */
    boolean update(ProviderCode entry);
}
