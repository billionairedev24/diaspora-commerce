package com.afrotransact.taxreturns.domain.tax;

/**
 * Monthly liability of one jurisdiction and currency.
 *
 * @param jurisdiction      state jurisdiction (US-TX)
 * @param currency          ISO currency
 * @param orders            orders with sales in the month
 * @param grossSalesMinor   all sales (taxed, exempt and not collected), net of refunds
 * @param taxableSalesMinor sales that carried tax, net of refunds
 * @param taxCollectedMinor tax collected on sales
 * @param taxRefundedMinor  tax given back by refunds (positive number)
 * @param netTaxMinor       collected − refunded: what AfroTransact remits
 */
public record LiabilityRow(String jurisdiction, String currency, long orders, long grossSalesMinor,
                           long taxableSalesMinor,
                           long taxCollectedMinor, long taxRefundedMinor, long netTaxMinor) {
}
