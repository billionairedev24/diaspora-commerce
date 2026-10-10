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
 * @param shippingTaxMinor  the part of {@code taxMinor} charged on the line's shipping (0 where shipping is not
 *                          taxable): tax on shipping alone rounded half up, never more than the line's tax
 */
public record TaxLineResult(String lineId, String sellerOrgId, TaxCategory category, long taxableMinor,
                            BigDecimal rate, long taxMinor, String jurisdiction, String localJurisdiction,
                            CollectedBy collectedBy, long shippingTaxMinor) {

    public TaxLineResult(String lineId, String sellerOrgId, TaxCategory category, long taxableMinor, BigDecimal rate,
                         long taxMinor, String jurisdiction, String localJurisdiction, CollectedBy collectedBy) {
        this(lineId, sellerOrgId, category, taxableMinor, rate, taxMinor, jurisdiction, localJurisdiction,
                collectedBy, 0L);
    }
}
