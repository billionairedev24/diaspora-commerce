package com.afrotransact.retail.application;

import com.afrotransact.retail.domain.MatchTolerance;

/**
 * Settings use cases need from configuration.
 *
 * @param baseCurrency     currency of landed cost, GL and approval thresholds (USD)
 * @param gs1CompanyPrefix UPC company prefix used to issue GTINs
 * @param retailOwnerOrg   inventory owner org of 1P stock; null lets inventory default to AfroTransact Retail
 * @param matchTolerance   three-way match tolerance
 */
public record RetailSettings(String baseCurrency, String gs1CompanyPrefix, String retailOwnerOrg,
                             MatchTolerance matchTolerance) {
}
