package com.afrotransact.taxreturns.domain.profile;

import java.time.Instant;

import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/**
 * One entry of the provider code map: the code a provider is sent for an internal OUTCOME code. Never per category or
 * per provider code, because one provider code can carry different outcomes in different scenarios.
 *
 * @param internalTaxCode outcome-level code
 * @param categoryCode    category of that outcome
 * @param outcome         the outcome
 * @param provider        provider
 * @param providerCode    provider's code, null when unmapped
 * @param active          the mapping may be used for a quote
 * @param verified        a person confirmed the provider treats this code as this outcome
 * @param verifiedNote    why it was verified
 * @param mapVersion      increments with each change
 * @param changedBy       who changed it last
 * @param changedAt       when
 */
public record ProviderCode(String internalTaxCode, String categoryCode, Outcome outcome, TaxProviderId provider,
                           String providerCode, boolean active, boolean verified, String verifiedNote, int mapVersion,
                           String changedBy, Instant changedAt) {

    public boolean mapped() {
        return providerCode != null && !providerCode.isBlank();
    }

    /** True when a quote may send this code: mapped, active and (when verification is required) verified. */
    public boolean usable(boolean requireVerified) {
        return mapped() && active && (verified || !requireVerified);
    }
}
