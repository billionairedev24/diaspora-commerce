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
 */
public record TaxTransaction(UUID id, TaxTransactionKind kind, UUID orderId, String orderNumber, String lineId,
                             String sourceRef, String sellerOrgId, String jurisdiction, String localJurisdiction,
                             String postalCode, String currency, long taxableMinor, long taxMinor,
                             Instant occurredAt) {

    /** First day of the (UTC) month the entry is reported in. */
    public LocalDate period() {
        return occurredAt.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }
}
