package com.afrotransact.orders.port;

import java.util.List;

/**
 * tax-returns-service {@code POST /v1/tax/quotes} (orders-service token with {@code tax:quote}): marketplace-facilitator
 * sales tax per line for one ship-to address. Throws {@link UpstreamFailure} when tax-returns is down or refuses.
 */
public interface TaxQuoteClient {

    Quote quote(Request request);

    /** Lines shipped to one address. */
    record Request(String currency, String country, String state, String postalCode, String city, List<Line> lines) {

        public Request {
            lines = List.copyOf(lines);
        }
    }

    /**
     * One order line.
     *
     * @param shippingMinor this line's share of its package's delivery charge (allocated per
     *                      {@code at.orders.tax.shipping-allocation}, TX-05 to TX-07)
     */
    record Line(String lineId, String atin, String sellerOrgId, String productType, int quantity,
                long unitPriceMinor, long discountMinor, long shippingMinor) {
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
