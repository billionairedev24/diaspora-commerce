package com.afrotransact.retail.domain;

import java.math.BigDecimal;

/**
 * One line of the match: PO price × received quantity (expected) against the invoiced amount, with the price
 * variance in percent of the PO price.
 */
public record MatchLine(int poLineNo, String sku, int orderedQty, int receivedQty, int previouslyInvoicedQty,
                        int invoicedQty, long poUnitPriceMinor, long invoiceUnitPriceMinor, long expectedAmountMinor,
                        long invoicedAmountMinor, BigDecimal priceVariancePercent, MatchLineStatus status) {
}
