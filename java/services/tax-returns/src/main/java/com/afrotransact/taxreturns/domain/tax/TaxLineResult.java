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
 * @param taxCode           our tax code the line was taxed under, or null
 * @param shippingTaxMinor  the part of {@code taxMinor} charged on the line's shipping (0 where shipping is not
 *                          taxable): tax on shipping alone rounded half up, never more than the line's tax
 * @param decision          the stored decision the line was taxed under (null when the caller gave a tax category)
 * @param addressPrecision  how exactly the address was resolved (ZIP or ADDRESS), null when no rate was looked up
 */
public record TaxLineResult(String lineId, String sellerOrgId, TaxCategory category, long taxableMinor,
                            BigDecimal rate, long taxMinor, String jurisdiction, String localJurisdiction,
                            CollectedBy collectedBy, long shippingTaxMinor, String taxCode,
                            LineDecision decision, String addressPrecision) {

    public TaxLineResult(String lineId, String sellerOrgId, TaxCategory category, long taxableMinor, BigDecimal rate,
                         long taxMinor, String jurisdiction, String localJurisdiction, CollectedBy collectedBy,
                         long shippingTaxMinor, String taxCode) {
        this(lineId, sellerOrgId, category, taxableMinor, rate, taxMinor, jurisdiction, localJurisdiction,
                collectedBy, shippingTaxMinor, taxCode, null, null);
    }

    public TaxLineResult(String lineId, String sellerOrgId, TaxCategory category, long taxableMinor, BigDecimal rate,
                         long taxMinor, String jurisdiction, String localJurisdiction, CollectedBy collectedBy,
                         long shippingTaxMinor) {
        this(lineId, sellerOrgId, category, taxableMinor, rate, taxMinor, jurisdiction, localJurisdiction,
                collectedBy, shippingTaxMinor, null);
    }

    public TaxLineResult(String lineId, String sellerOrgId, TaxCategory category, long taxableMinor, BigDecimal rate,
                         long taxMinor, String jurisdiction, String localJurisdiction, CollectedBy collectedBy) {
        this(lineId, sellerOrgId, category, taxableMinor, rate, taxMinor, jurisdiction, localJurisdiction,
                collectedBy, 0L);
    }

    public TaxLineResult withDecision(LineDecision d) {
        return new TaxLineResult(lineId, sellerOrgId, category, taxableMinor, rate, taxMinor, jurisdiction,
                localJurisdiction, collectedBy, shippingTaxMinor, d.internalTaxCode() == null ? taxCode
                : d.internalTaxCode(), d, addressPrecision);
    }

    public TaxLineResult withAddressPrecision(String precision) {
        return new TaxLineResult(lineId, sellerOrgId, category, taxableMinor, rate, taxMinor, jurisdiction,
                localJurisdiction, collectedBy, shippingTaxMinor, taxCode, decision, precision);
    }

    /** A line that cannot be taxed: no rate, no tax, the reason in the decision. */
    public static TaxLineResult blocked(TaxLineInput in, String jurisdiction, LineDecision d) {
        return new TaxLineResult(in.lineId(), in.sellerOrgId(), in.category(), 0L, null, 0L, jurisdiction, null,
                CollectedBy.NOT_COLLECTED, 0L, null, d, null);
    }
}
