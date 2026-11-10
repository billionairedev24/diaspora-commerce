package com.afrotransact.taxreturns.domain.tax;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One tax ledger entry per order line and source (order or refund). Refund entries carry negative amounts.
 *
 * @param id                entry id
 * @param kind              sale or refund
 * @param orderId           order
 * @param orderNumber       order number
 * @param lineId            order line
 * @param sourceRef         order id (sale) or payments refund id (refund); unique with kind and line
 * @param sellerOrgId       seller the tax is attributed to
 * @param jurisdiction      state jurisdiction (US-TX)
 * @param localJurisdiction local jurisdiction (US-TX-770) or null
 * @param postalCode        ship-to ZIP
 * @param currency          ISO currency
 * @param taxableMinor      taxable amount (negative for refunds)
 * @param taxMinor          tax (negative for refunds)
 * @param occurredAt        placement or refund time
 * @param internalTaxCode   outcome code the sale was priced under (null on sales recorded before it was kept)
 * @param outcome           EXEMPT or TAXABLE of that decision
 * @param ruleVersion       rule version of that decision
 * @param addressPrecision  ZIP or ADDRESS
 */
public record TaxTransaction(UUID id, TaxTransactionKind kind, UUID orderId, String orderNumber, String lineId,
                             String sourceRef, String sellerOrgId, String jurisdiction, String localJurisdiction,
                             String postalCode, String currency, long taxableMinor, long taxMinor,
                             Instant occurredAt, String internalTaxCode, String outcome, String ruleVersion,
                             String addressPrecision) {

    public TaxTransaction(UUID id, TaxTransactionKind kind, UUID orderId, String orderNumber, String lineId,
                          String sourceRef, String sellerOrgId, String jurisdiction, String localJurisdiction,
                          String postalCode, String currency, long taxableMinor, long taxMinor, Instant occurredAt) {
        this(id, kind, orderId, orderNumber, lineId, sourceRef, sellerOrgId, jurisdiction, localJurisdiction, postalCode,
                currency, taxableMinor, taxMinor, occurredAt, null, null, null, null);
    }

    /** The same entry with the decision of the sale. */
    public TaxTransaction withDecision(TaxDecisionStamp d) {
        return d == null ? this : new TaxTransaction(id, kind, orderId, orderNumber, lineId, sourceRef, sellerOrgId,
                jurisdiction, localJurisdiction, postalCode, currency, taxableMinor, taxMinor, occurredAt,
                d.internalTaxCode(), d.outcome(), d.ruleVersion(), d.addressPrecision());
    }

    public TaxDecisionStamp decision() {
        return new TaxDecisionStamp(outcome, internalTaxCode, ruleVersion, addressPrecision);
    }

    /** First day of the (UTC) month the entry is reported in. */
    public LocalDate period() {
        return occurredAt.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }
}
