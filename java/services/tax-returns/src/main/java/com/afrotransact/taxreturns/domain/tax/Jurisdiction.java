package com.afrotransact.taxreturns.domain.tax;

import java.util.Optional;

/**
 * Where a sale is taxed and at what rates, as a {@code JurisdictionResolver} found it.
 *
 * @param state            the state rule, empty outside the US or for a state without a rule
 * @param local            the local rate, empty when none applies
 * @param addressPrecision how exactly the address was resolved: {@code ZIP} (rate table by ZIP or 3-digit prefix, which
 *                         can violate TX-03 where a ZIP spans several jurisdictions) or {@code ADDRESS} (a provider
 *                         resolved the exact address)
 * @param source           who resolved it, e.g. {@code zip-table}
 */
public record Jurisdiction(Optional<StateTaxRule> state, Optional<LocalTaxRate> local, String addressPrecision,
                           String source) {

    public static final String ZIP = "ZIP";
    public static final String ADDRESS = "ADDRESS";
}
