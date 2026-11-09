package com.afrotransact.taxreturns.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;

/**
 * Quote request from orders (checkout) or the cart.
 *
 * @param currency  ISO currency of all amounts
 * @param shipTo    delivery address
 * @param lines     lines to tax
 * @param saleDate  the sale date (null = today in the rules' jurisdiction); decides date-bound exemptions
 * @param reference order or cart reference; SHADOW mode compares only quotes that carry one (real orders), unless
 *                  cart quotes are switched on
 */
public record TaxQuoteCommand(String currency, TaxAddress shipTo, List<Line> lines, LocalDate saleDate,
                              String reference) {

    public TaxQuoteCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public TaxQuoteCommand(String currency, TaxAddress shipTo, List<Line> lines) {
        this(currency, shipTo, lines, null, null);
    }

    /**
     * @param atin            variant ATIN: the tax decision attaches to it
     * @param sellerOrgId     seller of the line
     * @param taxCategory     legacy: GENERAL | GROCERY | PREPARED_FOOD chosen by the caller; when absent the stored
     *                        decision of the ATIN decides (there is no default)
     * @param productType     catalog product type (no longer decides tax)
     * @param sellerBehaviour the seller's behaviour facts for this listing (sold_heated, utensils_provided,
     *                        seller_is_bakery ...); part of the decision beside the variant's facts
     */
    public record Line(String lineId, String atin, String sellerOrgId, String taxCategory, String productType,
                       int quantity, long unitPriceMinor, long discountMinor, long shippingMinor,
                       Map<String, Object> sellerBehaviour) {

        public Line {
            sellerBehaviour = sellerBehaviour == null ? Map.of() : sellerBehaviour;
        }

        public Line(String lineId, String atin, String sellerOrgId, String taxCategory, String productType,
                    int quantity, long unitPriceMinor, long discountMinor, long shippingMinor) {
            this(lineId, atin, sellerOrgId, taxCategory, productType, quantity, unitPriceMinor, discountMinor,
                    shippingMinor, Map.of());
        }
    }
}
