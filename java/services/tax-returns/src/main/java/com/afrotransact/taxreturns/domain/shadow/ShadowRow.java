package com.afrotransact.taxreturns.domain.shadow;

import java.math.BigDecimal;
import java.time.Instant;

import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;

/**
 * One compared line: what the in-house engine and the shadow provider said. Logged, never charged.
 *
 * @param quotedAt         when
 * @param orderRef         order or cart reference when the caller sent one
 * @param lineId           caller's line id
 * @param atin             variant ATIN
 * @param provider         the shadow provider
 * @param state            ship-to state
 * @param categoryCode     category of the decision
 * @param internalTaxCode  outcome code
 * @param providerCode     code the provider was sent
 * @param kind             comparison result
 * @param inHouseOutcome   in-house outcome
 * @param inHouseRate      in-house combined rate
 * @param inHouseTaxMinor  in-house tax
 * @param providerOutcome  provider's outcome (taxed or not)
 * @param providerRate     provider's rate
 * @param providerTaxMinor provider's tax
 * @param providerError    why the provider could not answer
 * @param differenceMinor  provider minus in-house tax
 * @param currency         currency
 */
public record ShadowRow(Instant quotedAt, String orderRef, String lineId, String atin, TaxProviderId provider,
                        String state, String categoryCode, String internalTaxCode, String providerCode,
                        ShadowKind kind, Outcome inHouseOutcome, BigDecimal inHouseRate, Long inHouseTaxMinor,
                        Outcome providerOutcome, BigDecimal providerRate, Long providerTaxMinor, String providerError,
                        long differenceMinor, String currency) {
}
