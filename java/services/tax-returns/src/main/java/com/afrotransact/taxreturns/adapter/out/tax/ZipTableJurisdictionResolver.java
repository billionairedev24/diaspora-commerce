package com.afrotransact.taxreturns.adapter.out.tax;

import java.time.LocalDate;

import com.afrotransact.taxreturns.domain.tax.Jurisdiction;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.port.JurisdictionResolver;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import org.springframework.stereotype.Component;

/**
 * Default {@link JurisdictionResolver}: the rate table by ZIP (exact 5-digit entry first, then the 3-digit prefix).
 * Precision is {@code ZIP}. <b>Documented launch limitation (TX-03):</b> one ZIP can span several local jurisdictions, so a
 * ZIP-based local rate can be wrong for some addresses; every quote line records {@code addressPrecision=ZIP}. A provider
 * adapter resolving the exact address (precision ADDRESS) closes the gap without touching the rules.
 */
@Component
class ZipTableJurisdictionResolver implements JurisdictionResolver {

    private final TaxRateRepository rates;

    ZipTableJurisdictionResolver(TaxRateRepository rates) {
        this.rates = rates;
    }

    @Override
    public Jurisdiction resolve(TaxAddress shipTo, LocalDate saleDate) {
        return rates.table().jurisdiction(shipTo);
    }
}
