package com.afrotransact.taxreturns.domain.tax;

import java.math.BigDecimal;

/**
 * Tax of one line.
 *
 * @param lineId            caller's line id
 * @param sellerOrgId       seller the tax is attributed to
 * @param category          category used
 * @param taxableMinor      taxable amount (items after discount, plus shipping where taxable)
 * @param rate              combined rate applied (state + local)
 * @param taxMinor          tax, rounded half up to the minor unit once per line
 * @param jurisdiction      state jurisdiction ({@code US-TX})
 * @param localJurisdiction local jurisdiction ({@code US-TX-770}) or null
 * @param collectedBy       who collects
 */
public record TaxLineResult(String lineId, String sellerOrgId, TaxCategory category, long taxableMinor,
                            BigDecimal rate, long taxMinor, String jurisdiction, String localJurisdiction,
                            CollectedBy collectedBy) {
}
