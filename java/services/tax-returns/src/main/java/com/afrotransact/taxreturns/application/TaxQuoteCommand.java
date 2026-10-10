package com.afrotransact.taxreturns.application;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;

/**
 * Quote request from orders (checkout) or the cart.
 *
 * @param currency ISO currency of all amounts
 * @param shipTo   delivery address
 * @param lines    lines to tax
 */
public record TaxQuoteCommand(String currency, TaxAddress shipTo, List<Line> lines) {

    public TaxQuoteCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * @param taxCategory GENERAL | GROCERY | PREPARED_FOOD; when absent the product type decides
     * @param productType catalog product type
     */
    public record Line(String lineId, String atin, String sellerOrgId, String taxCategory, String productType,
                       int quantity, long unitPriceMinor, long discountMinor, long shippingMinor) {
    }
}
