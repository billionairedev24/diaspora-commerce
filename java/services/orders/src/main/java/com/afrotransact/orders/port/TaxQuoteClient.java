package com.afrotransact.orders.port;

import java.util.List;
import java.util.Map;

/**
 * tax-returns-service {@code POST /v1/tax/quotes} (orders-service token with {@code tax:quote}): marketplace-facilitator
 * sales tax per line for one ship-to address. Throws {@link UpstreamFailure} when tax-returns is down or refuses.
 */
public interface TaxQuoteClient {

    Quote quote(Request request);

    /**
     * Lines shipped to one address.
     *
     * @param orderId the order id: tax-returns' SHADOW mode compares only quotes that carry one; null for estimates
     */
    record Request(String currency, String country, String state, String postalCode, String city, List<Line> lines,
                   String orderId) {

        public Request {
            lines = List.copyOf(lines);
        }

        /** A quote without an order reference. */
        public Request(String currency, String country, String state, String postalCode, String city,
                       List<Line> lines) {
            this(currency, country, state, postalCode, city, lines, null);
        }
    }

    /**
     * One order line.
     *
     * @param shippingMinor this line's share of its package's delivery charge (allocated per
     *                      {@code at.orders.tax.shipping-allocation}, TX-05 to TX-07)
     * @param sellerBehaviour how this seller sells the item (sold_heated, heated_by_seller, utensils_provided,
     *                      seller_is_bakery) when catalog reports it for this seller's own listing; empty otherwise,
     *                      in which case a category that needs it blocks the line (never guessed)
     */
    record Line(String lineId, String atin, String sellerOrgId, String productType, int quantity,
                long unitPriceMinor, long discountMinor, long shippingMinor, Map<String, Object> sellerBehaviour) {

        public Line {
            sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
        }

        /** A line without seller behaviour. */
        public Line(String lineId, String atin, String sellerOrgId, String productType, int quantity,
                    long unitPriceMinor, long discountMinor, long shippingMinor) {
            this(lineId, atin, sellerOrgId, productType, quantity, unitPriceMinor, discountMinor, shippingMinor,
                    Map.of());
        }
    }

    /** The quote: one result per requested line. */
    record Quote(String provider, List<LineTax> lines) {

        public Quote {
            lines = List.copyOf(lines);
        }
    }

    /**
     * Tax of one line and the stored decision behind it.
     *
     * @param shippingTaxMinor the part of {@code taxMinor} charged on the line's shipping
     * @param decisionStatus   RESOLVED, or why the line has no definite decision (MISSING_FACTS, BLOCKED,
     *                         NEEDS_APPROVAL, REFINE_CODE, CONFLICT_OPEN); null when the answer carries none
     * @param outcome          EXEMPT, TAXABLE or BLOCKED
     * @param blockedReason    tax-returns' plain message when the line cannot be taxed
     * @param addressPrecision ZIP or ADDRESS when tax-returns reports it
     */
    record LineTax(String lineId, long taxMinor, long shippingTaxMinor, String jurisdiction,
                   String localJurisdiction, String decisionStatus, String outcome, String internalTaxCode,
                   String providerTaxCode, String ruleVersion, String blockedReason, String addressPrecision) {

        /** A line answered without any decision details (tests). */
        public LineTax(String lineId, long taxMinor, long shippingTaxMinor, String jurisdiction,
                       String localJurisdiction) {
            this(lineId, taxMinor, shippingTaxMinor, jurisdiction, localJurisdiction, null, null, null, null, null,
                    null, null);
        }
    }
}
