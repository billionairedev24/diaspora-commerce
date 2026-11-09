package com.afrotransact.taxreturns.port;

import java.time.LocalDate;

import com.afrotransact.taxreturns.domain.tax.Jurisdiction;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;

/**
 * Finds the jurisdiction (state and local rates) of a delivery address. The default adapter reads the ZIP-prefix rate
 * table (precision ZIP, the documented TX-03 launch limitation); a provider adapter can resolve the exact address.
 */
public interface JurisdictionResolver {

    Jurisdiction resolve(TaxAddress shipTo, LocalDate saleDate);
}
