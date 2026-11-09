package com.afrotransact.taxreturns.application;

import java.util.List;

import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/** The map from internal outcome codes to each provider's code. */
public interface ProviderCodeService {

    /** Entries, optionally of one provider or category, or only the unmapped ones (tax:read). */
    List<ProviderCode> list(TaxProviderId provider, String categoryCode, boolean unmappedOnly);

    /**
     * Sets or clears one provider's code for an outcome code (tax:classify-admin). The change is versioned and audited,
     * starts a new rule version and publishes tax.classification.changed. Changing the code resets {@code verified}
     * unless the caller verifies it in the same request.
     *
     * @throws com.afrotransact.platform.web.error.NotFoundException 404 tax.outcome-code-not-found
     */
    ProviderCode update(String internalTaxCode, Update update);

    /**
     * @param provider     provider
     * @param providerCode the code, null clears the mapping
     * @param verified     a person confirmed the provider treats this code as this outcome; null keeps it (reset when
     *                     the code changes)
     * @param reason       why (at least 3 characters)
     */
    record Update(TaxProviderId provider, String providerCode, Boolean verified, String reason) {
    }
}
